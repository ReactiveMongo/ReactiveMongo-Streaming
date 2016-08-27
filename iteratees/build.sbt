libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-iteratees" % "2.3.10" % "provided"
)

// Publish
apiURL := Some(url(s"https://reactivemongo.github.io/ReactiveMongo-Streaming/${Publish.majorVersion}/iteratees/api/"))

// Tests
fork in Test := false

testOptions in Test += Tests.Cleanup(Common.testCleanup)
