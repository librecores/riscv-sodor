srcDir          := $(abspath ..)
SBT := java -Xmx4096M -Xss8M -XX:MaxPermSize=128M -jar $(srcDir)/sbt/sbt-launch.jar $(SBT_FLAGS)


pynq_hdl/Top.v: $(srcDir)/src/fpgazynq/*.scala 
	cd $(srcDir) && $(SBT) "project fpgazynq" "run -td fpga/pynq_hdl"
	echo "\`ifndef SYNTHESIS\n\`define SYNTHESIS\n\`endif\n" > pynq_hdl/temp
	cat pynq_hdl/Top.v >> pynq_hdl/temp
	mv pynq_hdl/temp pynq_hdl/Top.v
	rm pynq_hdl/Top.anno.json pynq_hdl/Top.fir

pynq_ip_repo: pynq_hdl/Top.v
	vivado -mode batch -source pynq_package.tcl

pynq_bitstream: pynq_ip_repo
	vivado -mode batch -source pynq_bitstream.tcl

arty_hdl/Top.v: $(srcDir)/src/fpgaartix/*.scala 
	cd $(srcDir) && $(SBT) "project fpgaartix" "run -td fpga/arty_hdl"
	echo "\`ifndef SYNTHESIS\n\`define SYNTHESIS\n\`endif\n" > arty_hdl/temp
	cat arty_hdl/Top.v >> arty_hdl/temp
	mv arty_hdl/temp arty_hdl/Top.v
	rm arty_hdl/Top.anno.json arty_hdl/Top.fir

arty_ip_repo: arty_hdl/Top.v
	vivado -mode batch -source arty_package.tcl

arty_bitstream: arty_ip_repo
	vivado -mode batch -source arty_bitstream.tcl
