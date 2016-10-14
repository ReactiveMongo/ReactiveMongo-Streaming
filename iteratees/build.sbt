name := "reactivemongo-iteratees"

val playVer = sys.env.get("PLAY_VERSION").getOrElse("2.3.10")

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-iteratees" % playVer % "provided"
)

// Publish
apiURL := Some(url(s"https://reactivemongo.github.io/ReactiveMongo-Streaming/${Publish.majorVersion}/iteratees/api/"))

// Tests
fork in Test := false

testOptions in Test += Tests.Cleanup(Common.testCleanup)
