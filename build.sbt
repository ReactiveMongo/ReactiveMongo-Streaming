organization := "org.reactivemongo"

name := "reactivemongo-akkastream"

val majorVer = "0.12"

version := s"${majorVer}.0-SNAPSHOT"

val scalaMajorVer = "2.11"

scalaVersion := s"${scalaMajorVer}.8"

crossScalaVersions  := Seq("2.11.8")

scalacOptions in Compile ++= Seq(
  "-unchecked", "-deprecation", "-target:jvm-1.8", "-Ywarn-unused-import")

resolvers ++= Seq(
  Resolver.sonatypeRepo("snapshots"),
  "Typesafe repository releases" at "http://repo.typesafe.com/typesafe/releases/")

libraryDependencies ++= Seq(
  "org.reactivemongo" %% "reactivemongo" % version.value % "provided",
  "com.typesafe.akka" %% "akka-stream" % "2.4.8" % "provided")

// Test
fork in Test := false

testOptions in Test += Tests.Cleanup(cl => {
  import scala.language.reflectiveCalls
  val c = cl.loadClass("Common$")
  type M = { def close(): Unit }
  val m: M = c.getField("MODULE$").get(null).asInstanceOf[M]
  m.close()
})

libraryDependencies ++= (Seq(
  "specs2-core"
).map("org.specs2" %% _ % "3.8.3") ++ Seq(
  "org.slf4j" % "slf4j-simple" % "1.7.13",
  "org.reactivestreams" % "reactive-streams" % "1.0.0")).
  map(_ % Test)

def env(n: String): String = sys.env.get(n).getOrElse(n)

// Publish settings
publishMavenStyle := true

publishArtifact in Test := false

pomIncludeRepository := { _ => false }

licenses := Seq("Apache 2.0" ->
  url("http://www.apache.org/licenses/LICENSE-2.0"))

homepage := Some(url("http://reactivemongo.org"))

autoAPIMappings := true

apiURL := Some(url(
  s"https://reactivemongo.github.io/ReactiveMongo-AkkaStream/$majorVer/api/"))

pomExtra := (
  <scm>
    <url>git://github.com/ReactiveMongo/ReactiveMongo-AkkaStream.git</url>
    <connection>scm:git://github.com/ReactiveMongo/ReactiveMongo-AkkaStream.git</connection>
  </scm>
  <developers>
    <developer>
      <id>cchantep</id>
      <name>Cedric Chantepie</name>
      <url>https://github.com/cchantep/</url>
    </developer>
  </developers>)

val repoName = env("PUBLISH_REPO_NAME")
val repoUrl = env("PUBLISH_REPO_URL")

publishTo := Some(repoUrl).map(repoName at _)

credentials += Credentials(repoName, env("PUBLISH_REPO_ID"),
  env("PUBLISH_USER"), env("PUBLISH_PASS"))

// Format settings
import scalariform.formatter.preferences._
import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

scalariformSettings

ScalariformKeys.preferences := ScalariformKeys.preferences.value.
  setPreference(AlignParameters, false).
  setPreference(AlignSingleLineCaseStatements, true).
  setPreference(CompactControlReadability, false).
  setPreference(CompactStringConcatenation, false).
  setPreference(DoubleIndentClassDeclaration, true).
  setPreference(FormatXml, true).
  setPreference(IndentLocalDefs, false).
  setPreference(IndentPackageBlocks, true).
  setPreference(IndentSpaces, 2).
  setPreference(MultilineScaladocCommentsStartOnFirstLine, false).
  setPreference(PreserveSpaceBeforeArguments, false).
  setPreference(PreserveDanglingCloseParenthesis, true).
  setPreference(RewriteArrowSymbols, false).
  setPreference(SpaceBeforeColon, false).
  setPreference(SpaceInsideBrackets, false).
  setPreference(SpacesAroundMultiImports, true).
  setPreference(SpacesWithinPatternBinders, true)
