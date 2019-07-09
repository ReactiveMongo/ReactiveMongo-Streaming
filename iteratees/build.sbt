import com.typesafe.tools.mima.core._, ProblemFilters._

name := "reactivemongo-iteratees"

sourceDirectory := { 
  if (scalaVersion.value startsWith "2.13.") new java.io.File("/no/sources")
  else sourceDirectory.value
}

lazy val playVer = Def.setting[String] {
  sys.env.get("ITERATEES_VERSION").getOrElse {
    if (scalaVersion.value startsWith "2.11.") "2.3.10"
    else "2.6.1"
  }
}

libraryDependencies ++= {
  if (!scalaVersion.value.startsWith("2.13.")) {
    Seq(
      "com.typesafe.play" %% "play-iteratees" % playVer.value % Provided,
      "com.typesafe.akka" %% "akka-slf4j" % "2.5.23" % Test
    )
  } else {
    Seq.empty
  }
}

// MiMa
mimaBinaryIssueFilters ++= {
  val dmm = ProblemFilters.exclude[DirectMissingMethodProblem](_)
  val imt = ProblemFilters.exclude[IncompatibleMethTypeProblem](_)
  val pkg = "reactivemongo.play.iteratees"

  Seq(
    ProblemFilters.exclude[IncompatibleSignatureProblem](
      s"${pkg}.PlayIterateesFlattenedCursor.cursor"),
    dmm(s"${pkg}.PlayIterateesCursorImpl.enumerateResponses"),
    dmm(s"${pkg}.PlayIterateesCursorImpl.enumerateResponses$$default$$1"),
    dmm(s"${pkg}.PlayIterateesCursorImpl.enumerateResponses$$default$$2"),
    dmm(s"${pkg}.PlayIterateesCursorImpl.rawEnumerateResponses"),
    dmm(s"${pkg}.PlayIterateesCursorImpl.rawEnumerateResponses$$default$$1"),
    dmm(s"${pkg}.PlayIterateesCursorImpl.enumerateBulks"),
    dmm(s"${pkg}.PlayIterateesCursorImpl.enumerateBulks$$default$$1"),
    dmm(s"${pkg}.PlayIterateesCursorImpl.enumerateBulks$$default$$2"),
    dmm(s"${pkg}.PlayIterateesCursorImpl.toList"),
    dmm(s"${pkg}.PlayIterateesCursorImpl.toList$$default$$1"),
    dmm(s"${pkg}.PlayIterateesCursorImpl.toList$$default$$2"),
    dmm(s"${pkg}.PlayIterateesCursorImpl.enumerate"),
    dmm(s"${pkg}.PlayIterateesCursorImpl.enumerate$$default$$2"),
    dmm(s"${pkg}.PlayIterateesCursorImpl.enumerate$$default$$1"),
    imt(s"${pkg}.PlayIterateesCursorImpl.collect"),
    dmm(s"${pkg}.PlayIterateesCursorImpl.collect$$default$$1"),
    dmm(s"${pkg}.PlayIterateesCursorImpl.collect$$default$$2"))
}

// Publish
apiURL := Some(url(s"https://reactivemongo.github.io/ReactiveMongo-Streaming/${Publish.majorVersion}/iteratees/api/"))

// Tests
fork in Test := true
