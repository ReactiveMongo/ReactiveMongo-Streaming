organization := "org.reactivemongo"

name := "reactivemongo-akkastreams"

val reactiveMongoVer = "0.11.10"

version := s"$reactiveMongoVer-SNAPSHOT"

scalaVersion := "2.11.7"

crossScalaVersions  := Seq("2.11.7", "2.10.5")

scalacOptions in Compile ++= Seq(
  "-unchecked", "-deprecation", "-target:jvm-1.6", "-Ywarn-unused-import")

resolvers ++= Seq(
  Resolver.sonatypeRepo("snapshots"),
  "Typesafe repository releases" at "http://repo.typesafe.com/typesafe/releases/")

libraryDependencies ++= Seq(
  "org.reactivemongo" %% "reactivemongo" % reactiveMongoVer % "provided" cross CrossVersion.binary,
  "com.typesafe.akka" %% "akka-stream" % "2.4.2" cross CrossVersion.binary changing())

// Test
testOptions in Test += Tests.Cleanup(cl => {
  import scala.language.reflectiveCalls
  val c = cl.loadClass("Common$")
  type M = { def closeDriver(): Unit }
  val m: M = c.getField("MODULE$").get(null).asInstanceOf[M]
  m.closeDriver()
})

libraryDependencies ++= (Seq(
  "specs2-core"
).map("org.specs2" %% _ % "2.4.9") ++ Seq(
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

pomExtra := (
  <scm>
    <url>git://github.com/cchantep/RM-AkkaStream.git</url>
    <connection>scm:git://github.com/cchantep/RM-AkkaStream.git</connection>
  </scm>)

val repoName = env("PUBLISH_REPO_NAME")
val repoUrl = env("PUBLISH_REPO_URL")

publishTo := Some(repoUrl).map(repoName at _)

credentials += Credentials(repoName, env("PUBLISH_REPO_ID"),
  env("PUBLISH_USER"), env("PUBLISH_PASS"))
