import com.typesafe.tools.mima.core._, ProblemFilters._

name := "reactivemongo-iteratees"

Test / scalacOptions ++= Seq(
  "-P:silencer:globalFilters=Use\\ reactivemongo-bson-api")

sourceDirectory := {
  if (scalaBinaryVersion.value == "2.13") new java.io.File("/no/sources")
  else sourceDirectory.value
}

publishArtifact := (scalaBinaryVersion.value != "2.13")

publish := (Def.taskDyn {
  val ver = scalaBinaryVersion.value
  val go = publish.value

  Def.task {
    if (ver != "2.13") {
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
    else "2.5.25"
  }
}

libraryDependencies ++= {
  if (scalaBinaryVersion.value != "2.13") {
    val akkaTestDeps = Seq("actor", "slf4j")

    ("com.typesafe.play" %% "play-iteratees" % playVer.value % Provided) +: (
      akkaTestDeps.map { n =>
        "com.typesafe.akka" %% s"akka-$n" % akkaVer.value % Test
      })

  } else {
    Seq.empty
  }
}

// MiMa
mimaPreviousArtifacts := {
  if (scalaBinaryVersion.value == "2.13") Set.empty
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
