organization := "org.reactivemongo"

name := "reactivemongo-akkastreams"

version := "0.11.0-SNAPSHOT"

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
  "org.reactivemongo" %% "reactivemongo" % "0.11.0-SNAPSHOT" cross CrossVersion.binary changing(),
  "com.typesafe.akka" %% "akka-stream-experimental" % "1.0-M4" cross CrossVersion.binary)

libraryDependencies ++= Seq(
  "specs2-core"
).map("org.specs2" %% _ % "2.4.9" % Test)
