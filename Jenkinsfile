pipeline {
    agent { docker 'librecores/ci-osstools' }
    stages {
      stage("Test") {
        steps {
          sh "git submodule update --init --recursive -- riscv-fesvr"
          sh "cd riscv-fesvr; mkdir -p build; cd build; ../configure --prefix=/opt/riscv; make; cd ../../"
          sh "make run-emulator"
        }
      }
    }
}
