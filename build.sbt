import Dependencies._

ThisBuild / organization := "org.reactivemongo"

ThisBuild / scalaVersion := "2.12.20"

ThisBuild / crossScalaVersions := Seq(
  "2.11.12",
  scalaVersion.value,
  "2.13.14",
  "3.6.3"
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

lazy val `pekko-stream` = project.in(file("pekko-stream"))

lazy val streaming = (project in file("."))
  .settings(
    Seq(
      publish := ({}),
      publishTo := None,
      mimaPreviousArtifacts := Set.empty,
      mimaFailOnNoPrevious := false,
      libraryDependencies += (reactiveMongo % version.value % Provided)
        .exclude("com.typesafe.akka", "*")
    ) ++ Release.settings
  )
  .aggregate(iteratees, `akka-stream`, `pekko-stream`)
  .enablePlugins(ScalaUnidocPlugin)
  .disablePlugins(HighlightExtractorPlugin)
