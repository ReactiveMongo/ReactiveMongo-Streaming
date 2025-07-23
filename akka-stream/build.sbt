import com.typesafe.tools.mima.core._, ProblemFilters._

name := "reactivemongo-akkastream"

Compile / compile / scalacOptions ++= {
  if (scalaBinaryVersion.value == "2.11") {
    Seq.empty
  } else {
    Seq("-Wconf:cat=deprecation&msg=.*(fromFuture|UpdateBuilder).*:s")
  }
}

Test / compile / scalacOptions ++= {
  if (scalaBinaryVersion.value == "2.11") {
    Seq.empty
  } else {
    Seq("-Wconf:cat=deprecation&msg=.*(expectNoMessage|ActorMaterializer).*:s")
  }
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

lazy val akkaVer = Def.setting[String] {
  sys.env.get("AKKA_VERSION").getOrElse {
    if (scalaBinaryVersion.value == "3") "2.6.21"
    else if (scalaBinaryVersion.value == "2.11") "2.4.10"
    else "2.5.32"
  }
}

libraryDependencies ++= Dependencies.shared.value ++ Seq(
  "com.typesafe.akka" %% "akka-stream" % akkaVer.value,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVer.value % Test,
  "com.typesafe.akka" %% "akka-stream-testkit" % akkaVer.value % Test
)

libraryDependencies += "commons-codec" % "commons-codec" % "1.19.0" % Test

// MiMa
mimaBinaryIssueFilters ++= {
  val dmm = ProblemFilters.exclude[DirectMissingMethodProblem](_)
  val imt = ProblemFilters.exclude[IncompatibleMethTypeProblem](_)
  val inamp = ProblemFilters.exclude[InheritedNewAbstractMethodProblem](_)
  val pkg = "reactivemongo.akkastream"

  Seq(
    inamp("reactivemongo.akkastream.GridFSStreams.concat"),
    dmm("reactivemongo.akkastream.AkkaStreamCursorImpl.peek")
  )
}

// Publish
apiURL := Some(
  url(s"https://reactivemongo.github.io/ReactiveMongo-Streaming/${Publish.majorVersion}/akka-stream/api/")
)

// Tests
Test / fork := true
