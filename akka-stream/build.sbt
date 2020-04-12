import com.typesafe.tools.mima.core._, ProblemFilters._

name := "reactivemongo-akkastream"

// See https://github.com/scala/bug/issues/11880#issuecomment-583682673
Test / scalacOptions += "-no-specialization"

resolvers ++= Seq(
  Resolver.sonatypeRepo("snapshots"),
  // For Akka Stream Contrib TestKit
  "Tatami Snapshot" at "https://raw.github.com/cchantep/tatami/master/snapshots")

lazy val akkaVer = Def.setting[String] {
  sys.env.get("AKKA_VERSION").getOrElse {
    if (scalaBinaryVersion.value == "2.11") "2.4.10"
    else "2.5.25"
  }
}

val akkaContribVer = Def.setting[String] {
  if (!akkaVer.value.startsWith("2.4")) "0.10+9-a20362e2"
  else "0.6-6-g12a86f9-SNAPSHOT"
}

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % akkaVer.value,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVer.value % Test,
  "com.typesafe.akka" %% "akka-stream-testkit" % akkaVer.value % Test,
  "com.typesafe.akka" %% "akka-stream-contrib" % akkaContribVer.value % Test
)

libraryDependencies ++= Seq(
    organization.value %% "reactivemongo-bson-compat" % Common.driverVersion.value % Test)

// MiMa
mimaBinaryIssueFilters ++= {
  val dmm = ProblemFilters.exclude[DirectMissingMethodProblem](_)
  val imt = ProblemFilters.exclude[IncompatibleMethTypeProblem](_)
  val pkg = "reactivemongo.akkastream"

  Seq(
    dmm("reactivemongo.akkastream.AkkaStreamCursorImpl.peek"))
}

// Publish
apiURL := Some(url(s"https://reactivemongo.github.io/ReactiveMongo-Streaming/${Publish.majorVersion}/akka-stream/api/"))

// Tests
fork in Test := true
