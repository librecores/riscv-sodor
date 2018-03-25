//**************************************************************************
// RISCV Processor Front-end
//--------------------------------------------------------------------------
//
// Christopher Celio
// 2013 Jn 29
//
// Handles the fetching of instructions. 
// 
// The front-end will go into cruise-control and fetch whichever instrutions it
// feels like (probably just PC+4 for this simple pipeline), and it's the job
// of the datapath to assert "valid" on the "io.req" bundle when it wants to
// redirect the fetch PC.  Otherwise, io.req.valid is disasserted. 

// There are a number of games we can play with the front-end.  
//    - It can fetch doublewords, thus only using the memory port on every
//    other cycle.
//    - It can have a fetch buffer (combined with dw fetch) to hide memory port
//    hazards.
//     - It can use a stall cache (Krste Asanovic paper), which holds a cache
//     of previously stalled instructions.
//
// None of the above are implemented - I leave them as an excercise to the
// reader for now...


package RV32_3stage
{

import chisel3._
import chisel3.util._
import freechips.rocketchip.config._
import Constants._
import Common._


class FrontEndIO(implicit val p: Parameters) extends Bundle
{
   val cpu  = new FrontEndCpuIO
   val imem = new MemPortIo(p(xprlen))
}

 
class FrontEndReq(val xprlen: Int) extends Bundle
{
   val pc   = UInt(xprlen.W)
}
 

class FrontEndResp(val xprlen: Int) extends Bundle
{
   val pc   = UInt(xprlen.W) 
   val inst = UInt(xprlen.W)  // only support 32b insts for now
}

class FrontEndDebug(val xprlen: Int) extends Bundle
{
   val if_pc   = Output(UInt(xprlen.W))
   val if_inst = Output(UInt(xprlen.W))
}   

class FrontEndCpuIO(implicit val p: Parameters) extends Bundle
{
   val req = Flipped(new ValidIO(new FrontEndReq(p(xprlen))))
   val resp = new DecoupledIO(new FrontEndResp(p(xprlen)))
 
   val debug = new FrontEndDebug(p(xprlen))
}

class FrontEnd(implicit p: Parameters) extends Module
{
   val io = IO(new FrontEndIO)
   io.imem.resp.ready := true.B
   io.imem.req.bits.data := 0.U
   // io.cpu.req.valid -> to signal if instruction stream needs to be redirected like incase of jumps/exceptions
   //**********************************
   // Pipeline State Registers
   val if_reg_valid  = RegInit(false.B)
   val if_reg_pc     = RegInit(START_ADDR - 4.U)
    
   val exe_reg_valid = RegInit(false.B)
   val exe_reg_pc    = Reg(UInt(p(xprlen).W))
   val exe_reg_inst  = Reg(UInt(p(xprlen).W))

   //**********************************
   // Next PC Stage (if we can call it that)
   val if_pc_next = Wire(UInt(p(xprlen).W))
   val if_val_next = WireInit(true.B) // for now, always true. But instruction
                                // buffers, etc., could make that not the case.
   
   val if_pc_plus4 = if_reg_pc + 4.U               
   // io.cpu.req.valid used if memory replies always in next cycle like memory/SyncScratchpad           
   val redirect = RegInit(false.B)
   val redirectpc = Reg(UInt(32.W))

   when (io.cpu.req.valid && !io.imem.resp.valid && !io.imem.req.fire())
   {
      redirect := true.B
      redirectpc := io.cpu.req.bits.pc
   } .elsewhen (io.imem.req.fire()) {
      redirect := false.B // Instruction Stream Redirect"ed"
   } 
   when (io.cpu.resp.ready)
   {
      if_pc_next := Mux(io.cpu.req.valid,io.cpu.req.bits.pc,
            Mux(redirect,redirectpc,if_pc_plus4))
   } .otherwise {
      if_pc_next  := if_reg_pc
   }

   when (io.cpu.resp.ready)
   {
      when (io.imem.req.ready && io.imem.req.valid) {
         if_reg_pc    := if_pc_next
         if_reg_valid := true.B
      } .elsewhen (io.imem.resp.valid) {
         if_reg_valid := false.B
      }
   }

   io.imem.req.bits.addr := if_pc_next
   io.imem.req.valid     := !reset.toBool && io.cpu.resp.ready
   io.imem.req.bits.fcn  := M_XRD
   io.imem.req.bits.typ  := MT_WU

   //**********************************
   // Inst Fetch/Return Stage 
   // Store instruction when CPU is accepting new instructions in pipeline 
   val instrreg = Reg(UInt(p(xprlen).W)) 
   val respavail = RegInit(false.B)
   when (io.cpu.resp.ready)
   {
      when (!(io.cpu.req.valid || redirect)) {
         exe_reg_pc    := if_reg_pc
      }
      when (io.imem.resp.valid) {
         exe_reg_valid := !(io.cpu.req.valid || redirect)  
         exe_reg_inst  := io.imem.resp.bits.data
      } .otherwise {
         exe_reg_valid := respavail && !io.cpu.req.valid
         respavail := false.B
         exe_reg_inst := instrreg
      }
   } .elsewhen (io.imem.resp.valid) {
      instrreg := io.imem.resp.bits.data
      respavail := true.B
   }

   //**********************************
   // Execute Stage
   // (pass the instruction to the backend)
   io.cpu.resp.valid     := exe_reg_valid
   io.cpu.resp.bits.inst := exe_reg_inst
   io.cpu.resp.bits.pc   := exe_reg_pc
    
   //**********************************
   // only used for debugging 
   io.cpu.debug.if_pc := if_reg_pc
   io.cpu.debug.if_inst := io.imem.resp.bits.data
}

 
}
