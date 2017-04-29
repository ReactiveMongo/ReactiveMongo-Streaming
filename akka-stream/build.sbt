name := "reactivemongo-akkastream"

resolvers ++= Seq(
  // For Akka Stream Contrib TestKit
  "Tatami Snapshot" at "https://raw.github.com/cchantep/tatami/master/snapshots")

val akkaVer = Def.setting[String] {
  sys.env.get("AKKA_VERSION").getOrElse {
    if (scalaVersion.value startsWith "2.11.") "2.4.8"
    else "2.5.0"
  }
}

val akkaContribVer = Def.setting[String] {
  if (!akkaVer.value.startsWith("2.5")) "0.6"
  else "0.6-6-g12a86f9-SNAPSHOT"
}

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % akkaVer.value,
  "com.typesafe.akka" %% "akka-stream-testkit" % akkaVer.value % Test,
  "com.typesafe.akka" %% "akka-stream-contrib" % akkaContribVer.value % Test
)

// Publish
apiURL := Some(url(s"https://reactivemongo.github.io/ReactiveMongo-Streaming/${Publish.majorVersion}/akka-stream/api/"))

// Tests
fork in Test := false

testOptions in Test += Tests.Cleanup(Common.testCleanup)
