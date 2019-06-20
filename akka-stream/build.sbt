import com.typesafe.tools.mima.core._, ProblemFilters._
import com.typesafe.tools.mima.plugin.MimaPlugin.mimaDefaultSettings
import com.typesafe.tools.mima.plugin.MimaKeys.{
  mimaBinaryIssueFilters,
  mimaPreviousArtifacts
}

name := "reactivemongo-akkastream"

resolvers ++= Seq(
  Resolver.sonatypeRepo("snapshots"),
  // For Akka Stream Contrib TestKit
  "Tatami Snapshot" at "https://raw.github.com/cchantep/tatami/master/snapshots")

lazy val akkaVer = Def.setting[String] {
  sys.env.get("AKKA_VERSION").getOrElse {
    if (scalaVersion.value startsWith "2.11.") "2.4.10"
    else "2.5.23"
  }
}

val akkaContribVer = Def.setting[String] {
  if (akkaVer.value startsWith "2.5") "0.10+2-78d1b592"
  else "0.6-6-g12a86f9-SNAPSHOT"
}

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % akkaVer.value,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVer.value % Test,
  "com.typesafe.akka" %% "akka-stream-testkit" % akkaVer.value % Test,
  "com.typesafe.akka" %% "akka-stream-contrib" % akkaContribVer.value % Test
)

// MiMa
mimaBinaryIssueFilters ++= {
  val dmm = ProblemFilters.exclude[DirectMissingMethodProblem](_)
  val imt = ProblemFilters.exclude[IncompatibleMethTypeProblem](_)
  val pkg = "reactivemongo.akkastream"

  Seq(
    dmm(s"${pkg}.AkkaStreamCursorImpl.documentIterator"),
    dmm(s"${pkg}.AkkaStreamCursorImpl.enumerateResponses"),
    dmm(s"${pkg}.AkkaStreamCursorImpl.enumerateResponses$$default$$1"),
    dmm(s"${pkg}.AkkaStreamCursorImpl.enumerateResponses$$default$$2"),
    dmm(s"${pkg}.AkkaStreamCursorImpl.rawEnumerateResponses"),
    dmm(s"${pkg}.AkkaStreamCursorImpl.rawEnumerateResponses$$default$$1"),
    dmm(s"${pkg}.AkkaStreamCursorImpl.enumerateBulks"),
    dmm(s"${pkg}.AkkaStreamCursorImpl.enumerateBulks$$default$$1"),
    dmm(s"${pkg}.AkkaStreamCursorImpl.enumerateBulks$$default$$2"),
    dmm(s"${pkg}.AkkaStreamCursorImpl.toList"),
    dmm(s"${pkg}.AkkaStreamCursorImpl.toList$$default$$1"),
    dmm(s"${pkg}.AkkaStreamCursorImpl.toList$$default$$2"),
    dmm(s"${pkg}.AkkaStreamCursorImpl.enumerate"),
    dmm(s"${pkg}.AkkaStreamCursorImpl.enumerate$$default$$2"),
    dmm(s"${pkg}.AkkaStreamCursorImpl.enumerate$$default$$1"),
    imt(s"${pkg}.AkkaStreamCursorImpl.collect"),
    dmm(s"${pkg}.AkkaStreamCursorImpl.collect$$default$$1"),
    dmm(s"${pkg}.AkkaStreamCursorImpl.collect$$default$$2"))
}

// Publish
apiURL := Some(url(s"https://reactivemongo.github.io/ReactiveMongo-Streaming/${Publish.majorVersion}/akka-stream/api/"))

// Tests
fork in Test := true
