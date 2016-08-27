resolvers ++= Seq(
  // For Akka Stream TestKit 'tests' (see akka/akka#21028)
  "Tatami Releases" at "https://raw.github.com/cchantep/tatami/master/releases")

val akkaVer = "2.4.9"

def akkaStreamTestKit = "com.typesafe.akka" %% "akka-stream-testkit" % akkaVer

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % akkaVer,
  akkaStreamTestKit % Test,
  akkaStreamTestKit.classifier("tests") % Test
)

// Publish
apiURL := Some(url(s"https://reactivemongo.github.io/ReactiveMongo-Streaming/${Publish.majorVersion}/akka-stream/api/"))

// Tests
fork in Test := false

testOptions in Test += Tests.Cleanup(Common.testCleanup)
