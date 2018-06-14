package Sodor

import chisel3._

import Common.{SodorConfiguration, SimDTM}

class Top extends Module 
{
   val io = IO(new Bundle{
      val success = Output(Bool())
   })

   implicit val sodor_conf = SodorConfiguration()
   val tile = Module(new SodorTile)
   val dtm = Module(new SimDTM).connect(clock, reset.toBool, tile.io.dmi, io.success)
}

object elaborate {
  def main(args: Array[String]): Unit = {
    chisel3.Driver.execute(args, () => new Top)
  }
}
