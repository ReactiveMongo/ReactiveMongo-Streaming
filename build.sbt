import Dependencies._

organization in ThisBuild := "org.reactivemongo"

scalaVersion in ThisBuild := "2.12.6"

crossScalaVersions in ThisBuild := Seq("2.11.12", scalaVersion.value)

crossVersion in ThisBuild := CrossVersion.binary

resolvers in ThisBuild ++= Seq(
  "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/")

lazy val iteratees = project.in(file("iteratees")).
  settings(Common.settings: _*)

lazy val `akka-stream` = project.in(file("akka-stream")).
  settings(Common.settings: _*)

val travisEnv = taskKey[Unit]("Print Travis CI env")

lazy val streaming = (project in file(".")).settings(
  Seq(
    libraryDependencies += reactiveMongo % version.value % "provided",
    scalacOptions ++= Seq("-Ywarn-unused-import", "-unchecked"),
    scalacOptions in (Compile, doc) ++= List(
      "-skip-packages", "highlightextractor"),
    travisEnv in Test := { // test:travisEnv from SBT CLI
      val (akkaLower, akkaUpper) = "2.4.8" -> "2.5.13"
      val (playLower, playUpper) = "2.3.10" -> "2.6.1"
      val specs = List[(String, List[String])](
        "AKKA_VERSION" -> List(akkaLower, akkaUpper),
        "ITERATEES_VERSION" -> List(playLower, playUpper)
      )

      lazy val integrationEnv = specs.flatMap {
        case (key, values) => values.map(key -> _)
      }.combinations(specs.size).filterNot { flags =>
        /* chrono-compat exclusions */
        (flags.contains("AKKA_VERSION" -> akkaLower) && flags.
          contains("ITERATEES_VERSION" -> playUpper)) ||
        (flags.contains("AKKA_VERSION" -> akkaUpper) && flags.
          contains("ITERATEES_VERSION" -> playLower))
      }.collect {
        case flags if (flags.map(_._1).toSet.size == specs.size) =>
          flags.sortBy(_._1)
      }.toList

      @inline def integrationVars(flags: List[(String, String)]): String =
        flags.map { case (k, v) => s"$k=$v" }.mkString(" ")

      def integrationMatrix =
        integrationEnv.map(integrationVars).map { c => s"  - $c" }

      def matrix = (("env:" +: integrationMatrix :+
        "matrix: " :+ "  exclude: ") ++ (
        integrationEnv.flatMap { flags =>
          if (/* time-compat exclusions: */
              flags.contains("ITERATEES_VERSION" -> playUpper) ||
              flags.contains("AKKA_VERSION" -> akkaUpper)) {
            List(
              "    - scala: 2.11.12",
              s"      env: ${integrationVars(flags)}"
            )
          } else if (/* time-compat exclusions: */
            flags.contains("ITERATEES_VERSION" -> playLower) ||
              flags.contains("AKKA_VERSION" -> akkaLower)
          ) {
            List(
              s"    - scala: ${scalaVersion.value}",
              s"      env: ${integrationVars(flags)}"
            )
          } else List.empty[String]
        })
      ).mkString("\r\n")

      println(s"# Travis CI env\r\n$matrix")
    }
  ) ++ Publish.settings ++ Release.settings
).dependsOn(iteratees, `akka-stream`).
  aggregate(iteratees, `akka-stream`).
  enablePlugins(ScalaUnidocPlugin)
