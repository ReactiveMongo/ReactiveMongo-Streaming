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
  val silencerVer = "1.4.2"

  Seq(
    compilerPlugin("com.github.ghik" %% "silencer-plugin" % silencerVer),
    "com.github.ghik" %% "silencer-lib" % silencerVer % Provided)
}

Scapegoat.settings

ThisBuild / mimaPreviousArtifacts := {
  if (scalaVersion.value startsWith "2.13") Set.empty[ModuleID]
  else Set(organization.value %% name.value % "0.12.0")
}

lazy val iteratees = project.in(file("iteratees")).
  settings(Common.settings)

lazy val `akka-stream` = project.in(file("akka-stream")).
  settings(Common.settings)

val travisEnv = taskKey[Unit]("Print Travis CI env")

lazy val streaming = (project in file(".")).settings(
  Seq(
    mimaPreviousArtifacts := Set.empty,
    publishArtifact := false,
    publishTo := None,
    publish := {},
    mimaFailOnNoPrevious := false,
    libraryDependencies += reactiveMongo % version.value % "provided",
    scalacOptions in (Compile, doc) ++= List(
      "-skip-packages", "highlightextractor"),
  ) ++ Travis.settings ++ Release.settings
).dependsOn(iteratees, `akka-stream`).
  aggregate(iteratees, `akka-stream`).
  enablePlugins(ScalaUnidocPlugin)
