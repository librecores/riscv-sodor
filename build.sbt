val chiselVersion = System.getProperty("chiselVersion", "latest.release")
val defaultVersions = Map("chisel3" -> "latest.release",
                          "chisel-iotesters" -> "latest.release")

lazy val commonSettings = Seq (
  organization := "berkeley",
  version      := "3.0",
  scalaVersion := "2.11.12",
  traceLevel   := 15,
  resolvers ++= Seq(
    "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases"
  ),
  libraryDependencies ++= (Seq("chisel3","chisel-iotesters").map {
    dep: String => "edu.berkeley.cs" %% dep % sys.props.getOrElse(dep + "Version", defaultVersions(dep)) })
)  

lazy val rocketSettings = Seq (
  libraryDependencies ++= Seq("org.json4s" %% "json4s-jackson" % "3.5.0"),
  addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)
)

lazy val common = Project("common", file("common")).settings(commonSettings
  ++Seq(scalaSource in Compile := baseDirectory.value / "../src/common", 
        scalaSource in Test := baseDirectory.value / "../src/test",
        resourceDirectory in Compile := baseDirectory.value / "../vsrc"))
lazy val rv32_1stage = Project("rv32_1stage", file("rv32_1stage")).settings(commonSettings  
  ++Seq(scalaSource in Compile := baseDirectory.value / "../src/rv32_1stage")).dependsOn(common)
lazy val rv32_2stage = Project("rv32_2stage", file("rv32_2stage")).settings(commonSettings  
  ++Seq(scalaSource in Compile := baseDirectory.value / "../src/rv32_2stage")).dependsOn(common)
lazy val rv32_3stage = Project("rv32_3stage", file("rv32_3stage")).settings(commonSettings  
  ++Seq(scalaSource in Compile := baseDirectory.value / "../src/rv32_3stage")).dependsOn(common)
lazy val rv32_5stage = Project("rv32_5stage", file("rv32_5stage")).settings(commonSettings  
  ++Seq(scalaSource in Compile := baseDirectory.value / "../src/rv32_5stage")).dependsOn(common)
lazy val rv32_ucode  = Project("rv32_ucode", file("rv32_ucode")).settings(commonSettings  
  ++Seq(scalaSource in Compile := baseDirectory.value / "../src/rv32_ucode")).dependsOn(common)
lazy val fpgazynq  = Project("fpgazynq", file("fpgazynq")).settings(commonSettings
  ++Seq(scalaSource in Compile := baseDirectory.value / "../src/fpgazynq")
  ++Seq(resourceDirectory in Compile := baseDirectory.value / "../vsrc")).dependsOn(common,rv32_3stage,chiprocket)
lazy val fpgaartix  = Project("fpgaartix", file("fpgaartix")).settings(commonSettings
  ++Seq(scalaSource in Compile := baseDirectory.value / "../src/fpgaartix")
  ++Seq(resourceDirectory in Compile := baseDirectory.value / "../vsrc")).dependsOn(common,rv32_3stage,chiprocket,fpgazynq)
lazy val zynqsimtop  = Project("zynqsimtop", file("zynqsimtop")).settings(commonSettings
  ++Seq(scalaSource in Compile := baseDirectory.value / "../src/zynqsimtop")).dependsOn(fpgazynq)
lazy val macros  = Project("macros", file("macros")).settings(commonSettings
  ++Seq(scalaSource in Compile := baseDirectory.value / "../rocket-chip/macros/src/main/scala"))
lazy val hardfloat  = Project("hardfloat", file("hardfloat")).settings(commonSettings
  ++Seq(scalaSource in Compile := baseDirectory.value / "../rocket-chip/hardfloat/src/main/scala"))
lazy val chiprocket  = Project("chiprocket", file("chiprocket")).settings(commonSettings ++ rocketSettings
  ++Seq(scalaSource in Compile := baseDirectory.value / "../rocket-chip/src/main/scala")).dependsOn(macros,hardfloat,common)

