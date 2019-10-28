import Dependencies._

organization in ThisBuild := "org.reactivemongo"

scalaVersion in ThisBuild := "2.12.9"

crossScalaVersions in ThisBuild := Seq("2.11.12", scalaVersion.value, "2.13.1")

crossVersion in ThisBuild := CrossVersion.binary

resolvers += "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

resolvers in ThisBuild += "Sonatype Staging" at "https://oss.sonatype.org/content/repositories/staging/"

resolvers += "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

resolvers in ThisBuild ++= Seq(
  Resolver.sonatypeRepo("snapshots"),
  "Tatami Snapshots".at(
    "https://raw.github.com/cchantep/tatami/master/snapshots"))

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
    mimaFailOnNoPrevious := false,
    libraryDependencies += reactiveMongo % version.value % "provided",
    scalacOptions in (Compile, doc) ++= List(
      "-skip-packages", "highlightextractor"),
  ) ++ Travis.settings ++ Release.settings
).dependsOn(iteratees, `akka-stream`).
  aggregate(iteratees, `akka-stream`).
  enablePlugins(ScalaUnidocPlugin)
