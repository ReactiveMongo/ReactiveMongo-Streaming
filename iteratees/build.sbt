name := "reactivemongo-iteratees"

lazy val playVer = Def.setting[String] {
  sys.env.get("ITERATEES_VERSION").getOrElse {
    if (scalaVersion.value startsWith "2.11.") "2.3.10"
    else "2.6.1"
  }
}

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-iteratees" % playVer.value % Provided,
  "com.typesafe.akka" %% "akka-slf4j" % "2.5.13" % Test
)

// Publish
apiURL := Some(url(s"https://reactivemongo.github.io/ReactiveMongo-Streaming/${Publish.majorVersion}/iteratees/api/"))

// Tests
fork in Test := true
