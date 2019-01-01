val chiselVersion = System.getProperty("chiselVersion", "latest.release")
val defaultVersions = Map("chisel3" -> "latest.release",
                          "chisel-iotesters" -> "latest.release")

lazy val commonSettings = Seq (
  organization := "berkeley",
  version      := "3.0",
  scalaVersion := "2.12.4",
  traceLevel   := 15,
  scalacOptions ++= Seq("-deprecation","-unchecked","-Xsource:2.11"),
  resolvers ++= Seq(
    "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases"
  ),
  libraryDependencies ++= (Seq("chisel3","chisel-iotesters").map {
    dep: String => "edu.berkeley.cs" %% dep % sys.props.getOrElse(dep + "Version", defaultVersions(dep)) })
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

