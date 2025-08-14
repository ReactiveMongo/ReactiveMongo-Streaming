import Dependencies._

ThisBuild / organization := "org.reactivemongo"

ThisBuild / scalaVersion := "2.12.20"

ThisBuild / crossScalaVersions := Seq(
  "2.11.12",
  scalaVersion.value,
  "2.13.14",
  "3.7.2"
)

crossVersion := CrossVersion.binary

ThisBuild / credentials ++= Seq(
  Credentials(
    "", // Empty realm credential - this one is actually used by Coursier!
    "central.sonatype.com",
    Publish.env("SONATYPE_USER"),
    Publish.env("SONATYPE_PASS")
  )
)

ThisBuild / resolvers ++= Seq(
  "Central Testing repository" at "https://central.sonatype.com/api/v1/publisher/deployments/download",
  Resolver.typesafeRepo("releases")
)

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
