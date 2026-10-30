import com.typesafe.tools.mima.core._, ProblemFilters._

name := "reactivemongo-pekkostream"

lazy val disabled = Def.setting[Boolean] {
  scalaBinaryVersion.value == "2.11"
}

Common.usePekko := !disabled.value

sourceDirectory := {
  if (disabled.value) new java.io.File("/no/sources")
  else sourceDirectory.value
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

lazy val pekkoVer = sys.env.get("PEKKO_VERSION").getOrElse("1.7.1")

libraryDependencies ++= {
  if (disabled.value) {
    Seq.empty
  } else {
    Dependencies.shared.value ++ Seq(
      "org.apache.pekko" %% "pekko-stream" % pekkoVer,
      "org.apache.pekko" %% "pekko-slf4j" % pekkoVer % Test,
      "org.apache.pekko" %% "pekko-stream-testkit" % pekkoVer % Test,
      "commons-codec" % "commons-codec" % "1.15" % Test
    )
  }
}

// MiMa
//mimaBinaryIssueFilters ++= {}

// Publish
apiURL := Some(
  uri(s"https://reactivemongo.github.io/ReactiveMongo-Streaming/${Publish.majorVersion}/pekko-stream/api/")
)

// Tests
Test / fork := true
