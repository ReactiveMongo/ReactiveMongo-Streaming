import Dependencies._

ThisBuild / organization := "org.reactivemongo"

ThisBuild / scalaVersion := "2.12.16"

ThisBuild / crossScalaVersions := Seq(
  "2.11.12",
  scalaVersion.value,
  "2.13.8",
  "3.1.3"
)

crossVersion := CrossVersion.binary

ThisBuild / resolvers ++= {
  "Tatami Snapshots".at(
    "https://raw.github.com/cchantep/tatami/master/snapshots"
  ) +: Resolver.sonatypeOssRepos("snapshots") ++:
    Resolver.sonatypeOssRepos("staging")
}

lazy val iteratees = project.in(file("iteratees"))

lazy val `akka-stream` = project.in(file("akka-stream"))

lazy val streaming = (project in file("."))
  .settings(
    Seq(
      publish := ({}),
      publishTo := None,
      mimaPreviousArtifacts := Set.empty,
      mimaFailOnNoPrevious := false,
      libraryDependencies += (reactiveMongo % version.value % Provided)
        .exclude("com.typesafe.akka", "*"),
      Compile / doc / scalacOptions ++= List(
        "-skip-packages",
        "highlightextractor"
      )
    ) ++ Release.settings
  )
  .dependsOn(iteratees, `akka-stream`)
  .aggregate(iteratees, `akka-stream`)
  .enablePlugins(ScalaUnidocPlugin)
  .disablePlugins(HighlightExtractorPlugin)
