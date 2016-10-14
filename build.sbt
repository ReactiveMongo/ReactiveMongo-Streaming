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

val travisEnv = taskKey[Unit]("Print Travis CI env")

lazy val streaming = (project in file(".")).
  settings(unidocSettings: _*).
  settings(Publish.settings: _*).
  settings(
    libraryDependencies += reactiveMongo % version.value % "provided",
    scalacOptions ++= Seq("-Ywarn-unused-import", "-unchecked"),
    scalacOptions in (Compile, doc) ++= List(
      "-skip-packages", "highlightextractor"),
    travisEnv in Test := { // test:travisEnv from SBT CLI
      val specs = List[(String, List[String])](
        "AKKA_VERSION" -> List("2.4.8", "2.4.11"),
        "PLAY_VERSION" -> List("2.3.10", "2.6.0")
      )

      def matrix = specs.flatMap {
        case (key, values) => values.map(key -> _)
      }.combinations(specs.size).collect {
        case flags if (flags.map(_._1).toSet.size == specs.size) =>
          flags.sortBy(_._1).map { case (k, v) => s"$k=$v" }
      }.map { c => s"""  - ${c mkString " "}""" }

      println(s"""Travis CI env:\r\n${matrix.mkString("\r\n")}""")
    }
  ).
  dependsOn(iteratees, `akka-stream`).
  aggregate(iteratees, `akka-stream`)
