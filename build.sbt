import sbtunidoc.Plugin.UnidocKeys._
import Dependencies._

organization in ThisBuild := "org.reactivemongo"

version in ThisBuild := s"${Common.nextMajor}-SNAPSHOT"

scalaVersion in ThisBuild := "2.11.8"

resolvers in ThisBuild ++= Seq(
  "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/")

lazy val iteratees = project.in(file("iteratees")).
  settings(Common.settings: _*)

lazy val `akka-stream` = project.in(file("akka-stream")).
  settings(Common.settings: _*)

lazy val streaming = (project in file(".")).
  settings(unidocSettings: _*).
  settings(Publish.settings: _*).
  settings(
    libraryDependencies += reactiveMongo % version.value % "provided",
    scalacOptions ++= Seq("-Ywarn-unused-import", "-unchecked"),
    scalacOptions in (Compile, doc) ++= List(
      "-skip-packages", "highlightextractor")).
  dependsOn(iteratees, `akka-stream`).
  aggregate(iteratees, `akka-stream`)
