## PYNQ-Z1
You need to make sure the board files for pynq z1 are present. They can be downloaded from https://codeload.github.com/cathalmccabe/pynq-z1_board_files/zip/master . The board file should be present in Xilinx/Vivado/<version>/data/boards/board_files/ that is from the copy the folder pynq-z1_board_files-master/pynq-z1 to the above mentioned folder in vivado installation directory 

```bash
make pynq_bitstream
#builds pynq_ip_repo containing sodor as a packaged ip
```
Open xsdb console
```bash
connect
source ps7_init.tcl
ps7_init
ps7_post_config
# jumper JP4 should be on jtag
fpga -f myproj/project_1.runs/impl_1/design_1_wrapper.bit
source sample.tcl
```

To compile an assembly file using the following 
`riscv32-unknown-elf-gcc -static -mcmodel=medany -fvisibility=hidden -nostdlib -nostartfiles -Ttext 0x10000000 temp.S `

To verify disassemble using 
`riscv32-unknown-elf-objdump -d a.out`

## ARTY A7-35T
Board files need to be present which can be found here https://github.com/Digilent/vivado-boards 
```bash 
make arty_bitstream
```
Open xsdb console
```bash 
connect
fpga -f myproj/project_1.runs/impl_1/design_1_wrapper.bit
```
Check emulator/fpgaartix to execute asm and bmarks on arty

### Reference for this script
http://eliaskousk.teamdac.com/entry/automation-of-vivado-with-tcl-week-3-of-gsoc-2016