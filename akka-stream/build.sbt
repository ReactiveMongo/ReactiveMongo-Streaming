name := "reactivemongo-akkastream"

resolvers ++= Seq(
  // For Akka Stream Contrib TestKit
  "Tatami Releases" at "https://raw.github.com/cchantep/tatami/master/snapshots")

val akkaVer = sys.env.get("AKKA_VERSION").getOrElse("2.4.8")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % akkaVer,
  "com.typesafe.akka" %% "akka-stream-testkit" % akkaVer % Test,
  "com.typesafe.akka" %% "akka-stream-contrib" % "0.3-9-gaeac7b2" % Test
)

// Publish
apiURL := Some(url(s"https://reactivemongo.github.io/ReactiveMongo-Streaming/${Publish.majorVersion}/akka-stream/api/"))

// Tests
fork in Test := false

testOptions in Test += Tests.Cleanup(Common.testCleanup)
