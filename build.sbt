import Dependencies._

organization in ThisBuild := "org.reactivemongo"

scalaVersion in ThisBuild := "2.12.6"

crossScalaVersions in ThisBuild := Seq("2.11.12", scalaVersion.value, "2.13.0")

crossVersion in ThisBuild := CrossVersion.binary

resolvers in ThisBuild ++= Seq(
  Resolver.sonatypeRepo("snapshots"),
  "Tatami Snapshots".at(
    "https://raw.github.com/cchantep/tatami/master/snapshots"))

libraryDependencies in ThisBuild ++= {
  val silencerVer = "1.4.1"

  Seq(
    compilerPlugin("com.github.ghik" %% "silencer-plugin" % silencerVer),
    "com.github.ghik" %% "silencer-lib" % silencerVer % Provided)
}

Scapegoat.settings

lazy val iteratees = project.in(file("iteratees")).
  settings(Common.settings: _*)

lazy val `akka-stream` = project.in(file("akka-stream")).
  settings(Common.settings: _*)

val travisEnv = taskKey[Unit]("Print Travis CI env")

lazy val streaming = (project in file(".")).settings(
  Seq(
    libraryDependencies += reactiveMongo % version.value % "provided",
    scalacOptions in (Compile, doc) ++= List(
      "-skip-packages", "highlightextractor"),
  ) ++ Travis.settings ++ Publish.settings ++ Release.settings
).dependsOn(iteratees, `akka-stream`).
  aggregate(iteratees, `akka-stream`).
  enablePlugins(ScalaUnidocPlugin)
