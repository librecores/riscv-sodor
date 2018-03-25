package artix
{

import chisel3._
import Common._  
import zynq._ 
import util._
import RV32_3stage._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.config._

class Data extends Bundle {
  val data = Output(UInt(8.W))
}

class FIFOtoDMI(implicit p: Parameters) extends Module {
  val io = IO(new Bundle{
    val dmi = new DMIIO
    val fifo_out = DecoupledIO(new Data)
    val fifo_in = Flipped(DecoupledIO(new Data))
  })
  io.dmi.req.bits := 0.U.asTypeOf(new DMIReq(DMConsts.nDMIAddrSize))
  io.dmi.resp.ready := 0.U
  io.dmi.req.valid := 0.U
  io.fifo_out.bits.data := 0.U
  io.fifo_out.valid := 0.U
  io.fifo_in.ready := 0.U

  val addr = RegInit(0.U(7.W))
  val op = RegInit(0.U(2.W))
  val data = RegInit(0.U(32.W))

  /**           
    * Rx Header |W/R| Addr(7bits) |
    * Tx Header |    DMI Resp     | 
    */

  val ( s_idle :: s_rhdr   :: s_rdata3 :: s_rdata2 :: s_rdata1 :: s_rdata0 :: 
        s_thdr :: s_tdata3 :: s_tdata2 :: s_tdata1 :: s_tdata0 :: s_tterm :: Nil) = Enum(12)

  val fsm_state = RegInit(s_idle) 
  switch (fsm_state) {
    is (s_idle) {
      when (io.fifo_in.valid) {
        fsm_state := s_rhdr
      }
    }
    is (s_rhdr) {
      addr := io.fifo_in.bits.data(6,0)
      io.dmi.resp.ready := false.B
      when (io.fifo_in.bits.data(7)) {
        op := DMConsts.dmi_OP_WRITE
        fsm_state := s_rdata3
        io.fifo_in.ready := true.B
      } .otherwise {
        op := DMConsts.dmi_OP_READ
        fsm_state := Mux( io.dmi.req.ready, s_thdr, s_rhdr)
        io.dmi.req.bits.op := DMConsts.dmi_OP_READ
        io.dmi.req.bits.addr := io.fifo_in.bits.data(6,0)
        io.dmi.req.valid := true.B
        io.fifo_in.ready := io.dmi.req.ready
      }
    }
    is (s_rdata3) {
      io.fifo_in.ready := true.B
      when (io.fifo_in.fire()) {
        data := io.fifo_in.bits.data << 24.U 
        fsm_state := s_rdata2
      }
    }
    is (s_rdata2) {
      io.fifo_in.ready := true.B
      when (io.fifo_in.fire()) {
        data := data | (io.fifo_in.bits.data << 16.U) 
        fsm_state := s_rdata1
      }
    }
    is (s_rdata1) {
      io.fifo_in.ready := true.B
      when (io.fifo_in.fire()) {
        data := data | (io.fifo_in.bits.data << 8.U) 
        fsm_state := s_rdata0
      }
    }
    is (s_rdata0) {
      io.dmi.resp.ready := false.B
      io.dmi.req.bits.op := op
      io.dmi.req.bits.addr := addr
      io.dmi.req.bits.data := data | io.fifo_in.bits.data
      io.fifo_in.ready := io.dmi.req.ready
      io.dmi.req.valid := io.fifo_in.valid
      when (io.fifo_in.fire() && io.dmi.req.fire()) { 
        fsm_state := s_thdr 
      }
    }
    is (s_thdr) {
      io.fifo_out.bits.data := DMConsts.dmi_RESP_SUCCESS
      when ((op === DMConsts.dmi_OP_READ) && io.dmi.resp.valid) {
        io.dmi.resp.ready := false.B
        io.fifo_out.valid := true.B
        when (io.fifo_out.fire()) {
          fsm_state := s_tdata3
        }
      } .elsewhen ((op === DMConsts.dmi_OP_WRITE) && io.dmi.resp.valid) {
        io.dmi.resp.ready := true.B
        io.fifo_out.valid := true.B 
        when (io.fifo_out.fire()) {
          fsm_state := s_tterm
        }
      } .otherwise {
        fsm_state := s_thdr
      }
    }
    is (s_tdata3) {
      io.dmi.resp.ready := false.B
      io.fifo_out.bits.data := io.dmi.resp.bits.data(31,24) 
      io.fifo_out.valid := true.B
      when (io.fifo_out.fire() && io.dmi.resp.valid) {
        fsm_state := s_tdata2
      }
    }
    is (s_tdata2) {
      io.dmi.resp.ready := false.B
      io.fifo_out.bits.data := io.dmi.resp.bits.data(23,16) 
      io.fifo_out.valid := true.B
      when (io.fifo_out.fire() && io.dmi.resp.valid) {
        fsm_state := s_tdata1
      }
    }
    is (s_tdata1) {
      io.dmi.resp.ready := false.B
      io.fifo_out.bits.data := io.dmi.resp.bits.data(15,8) 
      io.fifo_out.valid := true.B
      when (io.fifo_out.fire() && io.dmi.resp.valid) {
        fsm_state := s_tdata0
      }
    }
    is (s_tdata0) {
      io.fifo_out.bits.data := io.dmi.resp.bits.data(7,0) 
      io.fifo_out.valid := true.B
      io.dmi.resp.ready := io.fifo_out.ready
      when (io.fifo_out.fire()) {
        fsm_state := s_tterm
      }
    }
    is (s_tterm) {
      op := 0.U
      addr := 0.U
      data := 0.U
      io.fifo_out.valid := true.B
      io.fifo_out.bits.data := "h0a".U // "\n"
      when (io.fifo_out.fire()) {
        fsm_state := s_idle
      }
      io.dmi.resp.ready := false.B
    }
  }
}

class SodorTileModule(outer: SodorTile)(implicit p: Parameters) extends LazyModuleImp(outer){
  val mem_axi4 = IO(HeterogeneousBag.fromNode(outer.mem_axi4.in))
  val fifo_out = IO(DecoupledIO(new Data)) // WIDTH
  val fifo_in = IO(Flipped(DecoupledIO(new Data))) // WIDTH

  val core   = Module(new Core())
  val fifotodmi   = Module(new FIFOtoDMI())
  val memory = outer.memory.module 
  val debug = Module(new DebugModule())

  mem_axi4.head <> outer.mem_axi4.in.head._1

  core.reset := debug.io.resetcore | reset.toBool

  core.io.dmem <> memory.io.core_ports(0)
  core.io.imem <> memory.io.core_ports(1)
  debug.io.debugmem <> memory.io.debug_port

  // DTM memory access
  debug.io.ddpath <> core.io.ddpath
  debug.io.dcpath <> core.io.dcpath 
  debug.io.dmi <> fifotodmi.io.dmi
  fifo_out <> fifotodmi.io.fifo_out
  fifotodmi.io.fifo_in <> fifo_in
}

class SodorTile(implicit p: Parameters) extends LazyModule
{
  val memory = LazyModule(new MemAccessToTL(num_core_ports=2))
  lazy val module = Module(new SodorTileModule(this))
  private val device = new MemoryDevice
  val config = p(ExtMem)
  val mmio = p(MMIO)
  val mem_axi4 = AXI4SlaveNode(Seq(  
    AXI4SlavePortParameters(
      slaves = Seq(AXI4SlaveParameters(
              address       = Seq(AddressSet(config.base, config.size-1),AddressSet(mmio.base, mmio.size-1)),
              resources     = device.reg,
              regionType    = RegionType.UNCACHED, 
              executable    = false,
              supportsWrite = TransferSizes(1, 4), 
              supportsRead  = TransferSizes(1, 4),
              interleavedId = Some(0))), 
            beatBytes = 4,minLatency =0)
  ))
   
  val tlxbar = LazyModule(new TLXbar)

  (mem_axi4 
        := AXI4Buffer()
        := AXI4UserYanker(Some(2))
        := AXI4IdIndexer(4)
        := TLToAXI4()
        := tlxbar.node)

  tlxbar.node := memory.masterDebug
  tlxbar.node := memory.masterInstr
  tlxbar.node := memory.masterData 

}
 
}
