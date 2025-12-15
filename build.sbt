import Dependencies._

ThisBuild / organization := "org.reactivemongo"

ThisBuild / scalaVersion := "2.12.21"

val scala3Lts = "3.3.7"

ThisBuild / crossScalaVersions := Seq(
  "2.11.12",
  scalaVersion.value,
  "2.13.16",
  scala3Lts
)

crossVersion := CrossVersion.binary

ThisBuild / credentials ++= sys.env.get("SONATYPE_USER").toSeq.map { user =>
  Credentials(
    "", // Empty realm credential - this one is actually used by Coursier!
    "central.sonatype.com",
    user,
    Publish.env("SONATYPE_PASS")
  )
}

ThisBuild / resolvers ++= Seq(
  "Central Testing repository" at "https://central.sonatype.com/api/v1/publisher/deployments/download",
  "Sonatype Snapshots" at "https://central.sonatype.com/repository/maven-snapshots/",
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
