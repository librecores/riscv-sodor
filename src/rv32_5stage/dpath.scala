//**************************************************************************
// RISCV Processor 5-Stage Datapath
//--------------------------------------------------------------------------
//
// Christopher Celio
// 2012 Jan 13
//
// TODO refactor stall, kill, fencei, flush signals. They're more confusing than they need to be.

package Sodor
{

import chisel3._
import chisel3.util.{MuxCase, MuxLookup, Cat, Fill}

import Constants._
import Common.{SodorConfiguration, CSRFile, Str, MemPortIo, CSR, Causes}

class DatToCtlIo(implicit val conf: SodorConfiguration) extends Bundle()
{
   val dec_inst    = Output(UInt(conf.xprlen.W))
   val exe_br_eq   = Output(Bool())
   val exe_br_lt   = Output(Bool())
   val exe_br_ltu  = Output(Bool())
   val exe_br_type = Output(UInt(4.W))

   val mem_ctrl_dmem_val = Output(Bool())

   val xcpt = Output(Bool()) // an exception occurred (detected in mem stage).
                                    // Kill the entire pipeline disregard stalls
                                    // and kill if,dec,exe stages. 
}

class DpathIo(implicit val conf: SodorConfiguration) extends Bundle()
{
   val imem = new MemPortIo(conf.xprlen)
   val dmem = new MemPortIo(conf.xprlen)
   val ctl  = Flipped(new CtlToDatIo())
   val dat  = new DatToCtlIo()
}

class DatPath(implicit val conf: SodorConfiguration) extends Module
{
   val io = IO(new DpathIo())
   io := DontCare

   //**********************************
   // Pipeline State Registers

   // Instruction Fetch State
   val if_reg_pc             = RegInit(START_ADDR)

   // Instruction Decode State
   val dec_reg_inst          = RegInit(BUBBLE)
   val dec_reg_pc            = RegInit(0.U(conf.xprlen.W))

   // Execute State
   val exe_reg_inst          = RegInit(BUBBLE)
   val exe_reg_pc            = RegInit(0.U(conf.xprlen.W))
   val exe_reg_wbaddr        = Reg(UInt(5.W))
   val exe_reg_rs1_addr      = Reg(UInt(5.W))
   val exe_reg_rs2_addr      = Reg(UInt(5.W))
   val exe_reg_op1_data      = Reg(UInt(conf.xprlen.W))
   val exe_reg_op2_data      = Reg(UInt(conf.xprlen.W))
   val exe_reg_rs2_data      = Reg(UInt(conf.xprlen.W))
   val exe_reg_ctrl_br_type  = RegInit(BR_N)
   val exe_reg_ctrl_op2_sel  = Reg(UInt())
   val exe_reg_ctrl_alu_fun  = Reg(UInt())
   val exe_reg_ctrl_wb_sel   = Reg(UInt())
   val exe_reg_ctrl_rf_wen   = RegInit(false.B)
   val exe_reg_ctrl_mem_val  = RegInit(false.B)
   val exe_reg_ctrl_mem_fcn  = RegInit(M_X)
   val exe_reg_ctrl_mem_typ  = RegInit(MT_X)
   val exe_reg_ctrl_csr_cmd  = RegInit(CSR.N)
   val exe_reg_ctrl_pc_sel   = Reg(UInt(2.W))

   // Memory State
   val mem_reg_pc            = Reg(UInt(conf.xprlen.W))
   val mem_reg_inst          = Reg(UInt(conf.xprlen.W))
   val mem_reg_alu_out       = Reg(Bits())
   val mem_reg_wbaddr        = Reg(UInt())
   val mem_reg_rs1_addr      = Reg(UInt())
   val mem_reg_rs2_addr      = Reg(UInt())
   val mem_reg_op1_data      = Reg(UInt(conf.xprlen.W))
   val mem_reg_op2_data      = Reg(UInt(conf.xprlen.W))
   val mem_reg_rs2_data      = Reg(UInt(conf.xprlen.W))
   val mem_reg_ctrl_rf_wen   = RegInit(false.B)
   val mem_reg_ctrl_mem_val  = RegInit(false.B)
   val mem_reg_ctrl_mem_fcn  = RegInit(M_X)
   val mem_reg_ctrl_mem_typ  = RegInit(MT_X)
   val mem_reg_ctrl_wb_sel   = Reg(UInt())
   val mem_reg_ctrl_csr_cmd  = RegInit(CSR.N)
   val jump_npc              = Reg(UInt(conf.xprlen.W))

   // Writeback State
   val wb_reg_wbaddr         = Reg(UInt())
   val wb_reg_wbdata         = Reg(UInt(conf.xprlen.W))
   val wb_reg_ctrl_rf_wen    = RegInit(false.B)


   //**********************************
   // Instruction Fetch Stage
   val if_pc_next          = Wire(UInt(32.W))
   val exe_brjmp_target    = Wire(UInt(32.W))
   val exe_jump_reg_target = Wire(UInt(32.W))
   val exception_target    = Wire(UInt(32.W))
   val xcpt                = Wire(Bool())

   when ((!io.ctl.dec_stall && !io.ctl.mem_stall) || xcpt)
   {
      if_reg_pc := if_pc_next
   }

   val if_pc_plus4 = (if_reg_pc + 4.asUInt(conf.xprlen.W))

   if_pc_next := Mux(io.ctl.exe_pc_sel === PC_4,      if_pc_plus4,
                 Mux(io.ctl.exe_pc_sel === PC_BRJMP,  exe_brjmp_target,
                 Mux(io.ctl.exe_pc_sel === PC_JALR,   exe_jump_reg_target,
                 /*Mux(io.ctl.exe_pc_sel === PC_EXC*/ exception_target)))

   // for a fencei, refetch the if_pc (assuming no stall, no branch, and no exception)
   when (io.ctl.fencei && io.ctl.exe_pc_sel === PC_4 && 
         !io.ctl.dec_stall && !io.ctl.mem_stall && !xcpt)
   {
      if_pc_next := if_reg_pc
   }

   // Instruction Memory
   io.imem.req.bits.addr := if_reg_pc
   val if_inst = io.imem.resp.bits.data

   when (xcpt)
   {
      dec_reg_inst := BUBBLE
   }
   .elsewhen (!io.ctl.dec_stall && !io.ctl.mem_stall)
   {
      when (io.ctl.if_kill)
      {
         dec_reg_inst := BUBBLE
      }
      .otherwise
      {
         dec_reg_inst := if_inst
      }

      dec_reg_pc := if_reg_pc
   }


   //**********************************
   // Decode Stage
   val dec_rs1_addr = dec_reg_inst(19, 15)
   val dec_rs2_addr = dec_reg_inst(24, 20)
   val dec_wbaddr   = dec_reg_inst(11, 7)


   // Register File
   val regfile = Module(new RegisterFile())
   regfile.io.rs1_addr := dec_rs1_addr
   regfile.io.rs2_addr := dec_rs2_addr
   val rf_rs1_data = regfile.io.rs1_data
   val rf_rs2_data = regfile.io.rs2_data
   regfile.io.waddr := wb_reg_wbaddr
   regfile.io.wdata := wb_reg_wbdata
   regfile.io.wen   := wb_reg_ctrl_rf_wen

   // immediates
   val imm_itype  = dec_reg_inst(31,20)
   val imm_stype  = Cat(dec_reg_inst(31,25), dec_reg_inst(11,7))
   val imm_sbtype = Cat(dec_reg_inst(31), dec_reg_inst(7), dec_reg_inst(30, 25), dec_reg_inst(11,8))
   val imm_utype  = dec_reg_inst(31, 12)
   val imm_ujtype = Cat(dec_reg_inst(31), dec_reg_inst(19,12), dec_reg_inst(20), dec_reg_inst(30,21))

   val imm_z = Cat(Fill(27,0.U), dec_reg_inst(19,15))

   // sign-extend immediates
   val imm_itype_sext  = Cat(Fill(20,imm_itype(11)), imm_itype)
   val imm_stype_sext  = Cat(Fill(20,imm_stype(11)), imm_stype)
   val imm_sbtype_sext = Cat(Fill(19,imm_sbtype(11)), imm_sbtype, 0.U)
   val imm_utype_sext  = Cat(imm_utype, Fill(12,0.U))
   val imm_ujtype_sext = Cat(Fill(11,imm_ujtype(19)), imm_ujtype, 0.U)

   // Operand 2 Mux
   val dec_alu_op2 = MuxCase(0.U, Array(
               (io.ctl.op2_sel === OP2_RS2)    -> rf_rs2_data,
               (io.ctl.op2_sel === OP2_ITYPE)  -> imm_itype_sext,
               (io.ctl.op2_sel === OP2_STYPE)  -> imm_stype_sext,
               (io.ctl.op2_sel === OP2_SBTYPE) -> imm_sbtype_sext,
               (io.ctl.op2_sel === OP2_UTYPE)  -> imm_utype_sext,
               (io.ctl.op2_sel === OP2_UJTYPE) -> imm_ujtype_sext
               )).asUInt

   // Bypass Muxes
   val exe_alu_out  = Wire(UInt(conf.xprlen.W))
   val mem_wbdata   = Wire(UInt(conf.xprlen.W))

   val dec_op1_data = Wire(UInt(conf.xprlen.W))
   val dec_op2_data = Wire(UInt(conf.xprlen.W))
   val dec_rs2_data = Wire(UInt(conf.xprlen.W))

   if (USE_FULL_BYPASSING)
   {
      // roll the OP1 mux into the bypass mux logic
      dec_op1_data := MuxCase(rf_rs1_data, Array(
                           ((io.ctl.op1_sel === OP1_IMZ)) -> imm_z,
                           ((io.ctl.op1_sel === OP1_PC)) -> dec_reg_pc,
                           ((exe_reg_wbaddr === dec_rs1_addr) && (dec_rs1_addr =/= 0.U) && exe_reg_ctrl_rf_wen) -> exe_alu_out,
                           ((mem_reg_wbaddr === dec_rs1_addr) && (dec_rs1_addr =/= 0.U) && mem_reg_ctrl_rf_wen) -> mem_wbdata,
                           ((wb_reg_wbaddr  === dec_rs1_addr) && (dec_rs1_addr =/= 0.U) &&  wb_reg_ctrl_rf_wen) -> wb_reg_wbdata
                           ))

      dec_op2_data := MuxCase(dec_alu_op2, Array(
                           ((exe_reg_wbaddr === dec_rs2_addr) && (dec_rs2_addr =/= 0.U) && exe_reg_ctrl_rf_wen && (io.ctl.op2_sel === OP2_RS2)) -> exe_alu_out,
                           ((mem_reg_wbaddr === dec_rs2_addr) && (dec_rs2_addr =/= 0.U) && mem_reg_ctrl_rf_wen && (io.ctl.op2_sel === OP2_RS2)) -> mem_wbdata,
                           ((wb_reg_wbaddr  === dec_rs2_addr) && (dec_rs2_addr =/= 0.U) &&  wb_reg_ctrl_rf_wen && (io.ctl.op2_sel === OP2_RS2)) -> wb_reg_wbdata
                           ))

      dec_rs2_data := MuxCase(rf_rs2_data, Array(
                           ((exe_reg_wbaddr === dec_rs2_addr) && (dec_rs2_addr =/= 0.U) && exe_reg_ctrl_rf_wen) -> exe_alu_out,
                           ((mem_reg_wbaddr === dec_rs2_addr) && (dec_rs2_addr =/= 0.U) && mem_reg_ctrl_rf_wen) -> mem_wbdata,
                           ((wb_reg_wbaddr  === dec_rs2_addr) && (dec_rs2_addr =/= 0.U) &&  wb_reg_ctrl_rf_wen) -> wb_reg_wbdata
                           ))
   }
   else
   {
      // Rely only on control interlocking to resolve hazards
      dec_op1_data := MuxCase(rf_rs1_data, Array(
                          ((io.ctl.op1_sel === OP1_IMZ)) -> imm_z,
                          ((io.ctl.op1_sel === OP1_PC))  -> dec_reg_pc
                          ))
      dec_rs2_data := rf_rs2_data
      dec_op2_data := dec_alu_op2
   }


   when ((io.ctl.dec_stall && !io.ctl.mem_stall) || xcpt)
   {
      // (kill exe stage)
      // insert NOP (bubble) into Execute stage on front-end stall (e.g., hazard clearing)
      exe_reg_inst          := BUBBLE
      exe_reg_wbaddr        := 0.U
      exe_reg_ctrl_rf_wen   := false.B
      exe_reg_ctrl_mem_val  := false.B
      exe_reg_ctrl_mem_fcn  := M_X
      exe_reg_ctrl_csr_cmd  := CSR.N
      exe_reg_ctrl_br_type  := BR_N
   }
   .elsewhen(!io.ctl.dec_stall && !io.ctl.mem_stall)
   {
      // no stalling...
      exe_reg_pc            := dec_reg_pc
      exe_reg_rs1_addr      := dec_rs1_addr
      exe_reg_rs2_addr      := dec_rs2_addr
      exe_reg_op1_data      := dec_op1_data
      exe_reg_op2_data      := dec_op2_data
      exe_reg_rs2_data      := dec_rs2_data
      exe_reg_ctrl_op2_sel  := io.ctl.op2_sel
      exe_reg_ctrl_alu_fun  := io.ctl.alu_fun
      exe_reg_ctrl_wb_sel   := io.ctl.wb_sel
      exe_reg_ctrl_pc_sel   := io.ctl.exe_pc_sel

      when (io.ctl.dec_kill)
      {
         exe_reg_inst          := BUBBLE
         exe_reg_wbaddr        := 0.U
         exe_reg_ctrl_rf_wen   := false.B
         exe_reg_ctrl_mem_val  := false.B
         exe_reg_ctrl_mem_fcn  := M_X
         exe_reg_ctrl_csr_cmd  := CSR.N
         exe_reg_ctrl_br_type  := BR_N
      }
      .otherwise
      {
         exe_reg_inst          := dec_reg_inst
         exe_reg_wbaddr        := dec_wbaddr
         exe_reg_ctrl_rf_wen   := io.ctl.rf_wen
         exe_reg_ctrl_mem_val  := io.ctl.mem_val
         exe_reg_ctrl_mem_fcn  := io.ctl.mem_fcn
         exe_reg_ctrl_mem_typ  := io.ctl.mem_typ
         exe_reg_ctrl_csr_cmd  := io.ctl.csr_cmd
         exe_reg_ctrl_br_type  := io.ctl.br_type
      }
   }

   //**********************************
   // Execute Stage

   val exe_alu_op1 = exe_reg_op1_data.asUInt
   val exe_alu_op2 = exe_reg_op2_data.asUInt

   // ALU
   val alu_shamt     = exe_alu_op2(4,0).asUInt
   val exe_adder_out = (exe_alu_op1 + exe_alu_op2)(conf.xprlen-1,0)

   //only for debug purposes right now until debug() works
   exe_alu_out := MuxCase(exe_reg_inst.asUInt, Array(
                  (exe_reg_ctrl_alu_fun === ALU_ADD)  -> exe_adder_out,
                  (exe_reg_ctrl_alu_fun === ALU_SUB)  -> (exe_alu_op1 - exe_alu_op2).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_AND)  -> (exe_alu_op1 & exe_alu_op2).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_OR)   -> (exe_alu_op1 | exe_alu_op2).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_XOR)  -> (exe_alu_op1 ^ exe_alu_op2).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_SLT)  -> (exe_alu_op1.asSInt < exe_alu_op2.asSInt).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_SLTU) -> (exe_alu_op1 < exe_alu_op2).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_SLL)  -> ((exe_alu_op1 << alu_shamt)(conf.xprlen-1, 0)).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_SRA)  -> (exe_alu_op1.asSInt >> alu_shamt).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_SRL)  -> (exe_alu_op1 >> alu_shamt).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_COPY_1)-> exe_alu_op1,
                  (exe_reg_ctrl_alu_fun === ALU_COPY_2)-> exe_alu_op2
                  ))

   // Branch/Jump Target Calculation
   val brjmp_offset    = exe_reg_op2_data
   exe_brjmp_target    := exe_reg_pc + brjmp_offset
   exe_jump_reg_target := Cat(exe_alu_out(31,1), 0.U(1.W))

   val exe_pc_plus4    = (exe_reg_pc + 4.U)(conf.xprlen-1,0)

   when (xcpt)
   {
      mem_reg_pc            := BUBBLE
      mem_reg_ctrl_rf_wen   := false.B
      mem_reg_ctrl_mem_val  := false.B
      mem_reg_ctrl_csr_cmd  := false.B
      jump_npc              := 0.U
   } .elsewhen (!io.ctl.mem_stall) {
      mem_reg_pc            := exe_reg_pc
      mem_reg_inst          := exe_reg_inst
      mem_reg_alu_out       := Mux((exe_reg_ctrl_wb_sel === WB_PC4), exe_pc_plus4, exe_alu_out)
      mem_reg_wbaddr        := exe_reg_wbaddr
      mem_reg_rs1_addr      := exe_reg_rs1_addr
      mem_reg_rs2_addr      := exe_reg_rs2_addr
      mem_reg_op1_data      := exe_reg_op1_data
      mem_reg_op2_data      := exe_reg_op2_data
      mem_reg_rs2_data      := exe_reg_rs2_data
      mem_reg_ctrl_rf_wen   := exe_reg_ctrl_rf_wen
      mem_reg_ctrl_mem_val  := exe_reg_ctrl_mem_val
      mem_reg_ctrl_mem_fcn  := exe_reg_ctrl_mem_fcn
      mem_reg_ctrl_mem_typ  := exe_reg_ctrl_mem_typ
      mem_reg_ctrl_wb_sel   := exe_reg_ctrl_wb_sel
      mem_reg_ctrl_csr_cmd  := exe_reg_ctrl_csr_cmd
      jump_npc              :=  Mux(io.ctl.exe_pc_sel === PC_BRJMP, exe_brjmp_target,
                                Mux(io.ctl.exe_pc_sel === PC_JALR, exe_jump_reg_target, 0.U))
   }

   //**********************************
   // Memory Stage
   // Control Status Registers
   val csr = Module(new CSRFile())
   csr.io := DontCare
   csr.io.rw.addr  := mem_reg_inst(CSR_ADDR_MSB,CSR_ADDR_LSB)
   csr.io.rw.wdata := mem_reg_alu_out
   csr.io.rw.cmd   := mem_reg_ctrl_csr_cmd

   csr.io.retire    := !(RegNext(mem_reg_inst) === BUBBLE) // can be made better
   csr.io.pc        := mem_reg_pc
   exception_target := csr.io.evec

   val ma_load          = Wire(Bool())
   val ma_str           = Wire(Bool())
   val ma_jump         = Wire(Bool())
   val ls_addr_ma_valid  = MuxLookup(mem_reg_ctrl_mem_typ(1,0) ,false.B, Array( 2.U -> mem_reg_alu_out(0), 3.U -> mem_reg_alu_out(1,0).orR ))
   ma_jump      := jump_npc(1,0).orR
   ma_load      := !mem_reg_ctrl_mem_fcn.toBool && mem_reg_ctrl_mem_val && ls_addr_ma_valid
   ma_str       := mem_reg_ctrl_mem_fcn.toBool && mem_reg_ctrl_mem_val && ls_addr_ma_valid
   csr.io.xcpt  := ma_load || ma_str || ma_jump || io.ctl.mem_illegal
   csr.io.cause := MuxCase(0.U, Array(
                     ma_jump -> Causes.misaligned_fetch.U,
                     io.ctl.mem_illegal -> Causes.illegal_instruction.U,
                     ma_load -> Causes.misaligned_load.U,
                     ma_str  -> Causes.misaligned_store.U ))
   csr.io.tval  := MuxCase(0.U, Array(
                     ma_jump -> jump_npc,
                     io.ctl.mem_illegal -> mem_reg_inst,
                     ma_load -> mem_reg_alu_out,
                     ma_str  -> mem_reg_alu_out ))
   xcpt := ma_jump  || ma_load || ma_str || io.ctl.mem_illegal || csr.io.eret
   io.dat.xcpt := xcpt

   // Add your own uarch counters here!
   csr.io.counters.foreach(_.inc := false.B)


   // WB Mux
   mem_wbdata := MuxCase(mem_reg_alu_out, Array(
                  (mem_reg_ctrl_wb_sel === WB_ALU) -> mem_reg_alu_out,
                  (mem_reg_ctrl_wb_sel === WB_PC4) -> mem_reg_alu_out,
                  (mem_reg_ctrl_wb_sel === WB_MEM) -> io.dmem.resp.bits.data,
                  (mem_reg_ctrl_wb_sel === WB_CSR) -> csr.io.rw.rdata
                  ))


   //**********************************
   // Writeback Stage
   when (!io.ctl.mem_stall)
   {
      wb_reg_wbaddr        := mem_reg_wbaddr
      wb_reg_wbdata        := mem_wbdata
      wb_reg_ctrl_rf_wen   := Mux(xcpt, false.B, mem_reg_ctrl_rf_wen)
   } .otherwise {
      wb_reg_ctrl_rf_wen   := false.B
   }



   //**********************************
   // External Signals

   // datapath to controlpath outputs
   io.dat.dec_inst   := dec_reg_inst
   io.dat.exe_br_eq  := (exe_reg_op1_data === exe_reg_rs2_data)
   io.dat.exe_br_lt  := (exe_reg_op1_data.asSInt < exe_reg_rs2_data.asSInt)
   io.dat.exe_br_ltu := (exe_reg_op1_data.asUInt < exe_reg_rs2_data.asUInt)
   io.dat.exe_br_type:= exe_reg_ctrl_br_type

   io.dat.mem_ctrl_dmem_val := mem_reg_ctrl_mem_val

   // datapath to data memory outputs
   io.dmem.req.valid     := mem_reg_ctrl_mem_val && !ma_str
   io.dmem.req.bits.addr := mem_reg_alu_out.asUInt
   io.dmem.req.bits.fcn  := mem_reg_ctrl_mem_fcn
   io.dmem.req.bits.typ  := mem_reg_ctrl_mem_typ
   io.dmem.req.bits.data := mem_reg_rs2_data

   // Printout
   printf("Cyc= %d (0x%x, 0x%x, 0x%x, 0x%x, 0x%x) WB[%c%c %x: 0x%x] %c %c %c ExeInst: DASM(%x)\n"
      , csr.io.time(31,0)
      , if_reg_pc
      , dec_reg_pc
      , exe_reg_pc
      , RegNext(exe_reg_pc)
      , RegNext(RegNext(exe_reg_pc))
      , Mux(wb_reg_ctrl_rf_wen, Str("M"), Str(" ")) 
      , Mux(mem_reg_ctrl_rf_wen, Str("Z"), Str(" "))
      , wb_reg_wbaddr
      , wb_reg_wbdata
      , Mux(io.ctl.mem_stall, Str("F"),   //FREEZE-> F 
        Mux(io.ctl.dec_stall, Str("S"), Str(" ")))  //STALL->S
      , Mux(io.ctl.exe_pc_sel === 1.U, Str("B"),  //BJ -> B
        Mux(io.ctl.exe_pc_sel === 2.U, Str("J"),   //JR -> J
        Mux(io.ctl.exe_pc_sel === 3.U, Str("E"),   //EX -> E
        Mux(io.ctl.exe_pc_sel === 0.U, Str(" "), Str("?")))))
      , Mux(csr.io.illegal, Str("X"), Str(" "))
      , Mux(xcpt, BUBBLE, exe_reg_inst)
      )

}


}
