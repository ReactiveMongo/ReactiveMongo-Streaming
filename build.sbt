organization := "org.reactivemongo"

name := "reactivemongo-akkastreams"

version := "0.11.6-SNAPSHOT"

scalaVersion := "2.11.7"

crossScalaVersions  := Seq("2.11.7", "2.10.4")

testOptions in Test += Tests.Cleanup(cl => {
  import scala.language.reflectiveCalls
  val c = cl.loadClass("Common$")
  type M = { def closeDriver(): Unit }
  val m: M = c.getField("MODULE$").get(null).asInstanceOf[M]
  m.closeDriver()
})

resolvers ++= Seq(
  Resolver.sonatypeRepo("snapshots"),
  "Typesafe repository releases" at "http://repo.typesafe.com/typesafe/releases/")

libraryDependencies ++= Seq(
  "org.reactivemongo" %% "reactivemongo" % "0.11.6" % "provided" cross CrossVersion.binary,
  "com.typesafe.akka" %% "akka-stream-experimental" % "1.0" cross CrossVersion.binary changing())

libraryDependencies ++= Seq(
  "specs2-core"
).map("org.specs2" %% _ % "2.4.9" % Test)

publishTo := Some("Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/")

/*
credentials := Seq(Credentials(
  "Sonatype Nexus Repository Manager",
  "oss.sonatype.org",
  sys.env.get("SONATYPE_USER").getOrElse(throw new RuntimeException("no SONATYPE_USER defined")),
  sys.env.get("SONATYPE_PASSWORD").getOrElse(throw new RuntimeException("no SONATYPE_PASSWORD defined"))
))
*/
