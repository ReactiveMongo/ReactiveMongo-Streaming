import com.typesafe.tools.mima.core._, ProblemFilters._

name := "reactivemongo-iteratees"

Test / scalacOptions ++= {
  if (!scalaBinaryVersion.value.startsWith("3")) {
    Seq("-P:silencer:globalFilters=Use\\ reactivemongo-bson-api")
  } else {
    Seq.empty
  }
}

lazy val disabled = Def.setting[Boolean] {
  val v = scalaBinaryVersion.value

  v == "2.13" || v == "3"
}

sourceDirectory := {
  if (disabled.value) new java.io.File("/no/sources")
  else sourceDirectory.value
}

publishArtifact := (scalaBinaryVersion.value != "2.13")

publish := (Def.taskDyn {
  val ver = scalaBinaryVersion.value
  val go = publish.value

  Def.task {
    if (!disabled.value) {
      go
    }
  }
}).value

lazy val playVer = Def.setting[String] {
  sys.env.get("ITERATEES_VERSION").getOrElse {
    if (scalaBinaryVersion.value == "2.11") "2.3.10"
    else "2.6.1"
  }
}

lazy val akkaVer = Def.setting[String] {
  sys.env.get("AKKA_VERSION").getOrElse {
    if (scalaBinaryVersion.value == "2.11") "2.4.10"
    else "2.5.32"
  }
}

libraryDependencies ++= {
  if (!disabled.value) {
    val akkaTestDeps = Seq("actor", "slf4j")

    Dependencies.shared.value ++: (
      "com.typesafe.play" %% "play-iteratees" % playVer.value % Provided) +: (
      akkaTestDeps.map { n =>
        "com.typesafe.akka" %% s"akka-$n" % akkaVer.value % Test
      })

  } else {
    Seq.empty
  }
}

// MiMa
mimaPreviousArtifacts := {
  if (disabled.value) Set.empty
  else mimaPreviousArtifacts.value
}

mimaBinaryIssueFilters ++= {
  val dmm = ProblemFilters.exclude[DirectMissingMethodProblem](_)
  val imt = ProblemFilters.exclude[IncompatibleMethTypeProblem](_)
  val pkg = "reactivemongo.play.iteratees"

  Seq(
    dmm(s"${pkg}.PlayIterateesCursorImpl.peek"),
    dmm(s"${pkg}.PlayIterateesCursorImpl.responseEnumerator"),
    dmm(s"${pkg}.PlayIterateesCursorImpl.responseEnumerator$$default$$1"),
    dmm(s"${pkg}.PlayIterateesCursorImpl.responseEnumerator$$default$$2"),
    dmm(s"${pkg}.PlayIterateesCursor.responseEnumerator"),
    dmm(s"${pkg}.PlayIterateesCursor.responseEnumerator$$default$$1"),
    dmm(s"${pkg}.PlayIterateesCursor.responseEnumerator$$default$$2"),
    dmm(s"${pkg}.PlayIterateesFlattenedCursor.responseEnumerator"),
    dmm(s"${pkg}.PlayIterateesFlattenedCursor.responseEnumerator$$default$$1"),
    dmm(s"${pkg}.PlayIterateesFlattenedCursor.responseEnumerator$$default$$2")
  )
}

// Publish
apiURL := Some(url(s"https://reactivemongo.github.io/ReactiveMongo-Streaming/${Publish.majorVersion}/iteratees/api/"))

// Tests
Test / fork := true
