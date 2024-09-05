import com.typesafe.tools.mima.core._, ProblemFilters._

name := "reactivemongo-pekkostream"

Common.usePekko := true

crossScalaVersions ~= {
  _.filterNot(v => v.startsWith("2.11"))
}

mimaPreviousArtifacts := Set.empty

Compile / compile / scalacOptions ++= {
  val v = scalaBinaryVersion.value

  Seq("-Wconf:cat=deprecation&msg=.*(fromFuture|UpdateBuilder).*:s")
}

Test / compile / scalacOptions ++= {
  Seq("-Wconf:cat=deprecation&msg=.*expectNoMessage.*:s")
}

// See https://github.com/scala/bug/issues/11880#issuecomment-583682673
Test / scalacOptions ++= {
  if (scalaBinaryVersion.value != "3") {
    Seq("-no-specialization")
  } else {
    Seq.empty
  }
}

Test / sources := {
  if (scalaBinaryVersion.value == "3") {
    (Test / sources).value.filter { f => f.getName.indexOf("README-md") != -1 }
  } else {
    (Test / sources).value
  }
}

val pekkoVer = "1.1.0"

libraryDependencies ++= Dependencies.shared.value ++ Seq(
  "org.apache.pekko" %% "pekko-stream" % pekkoVer,
  "org.apache.pekko" %% "pekko-slf4j" % pekkoVer % Test,
  "org.apache.pekko" %% "pekko-stream-testkit" % pekkoVer % Test,
  "com.typesafe.akka" %% "akka-stream" % "2.6.21" % Test // needed by org.reactivemongo:reactivemongo
)

libraryDependencies += "commons-codec" % "commons-codec" % "1.15" % Test

// MiMa
//mimaBinaryIssueFilters ++= {}

// Publish
apiURL := Some(
  url(s"https://reactivemongo.github.io/ReactiveMongo-Streaming/${Publish.majorVersion}/pekko-stream/api/")
)

// Tests
Test / fork := true
