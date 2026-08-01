import Dependencies._
import com.typesafe.tools.mima.core._, ProblemFilters._

ThisBuild / organization := "org.reactivemongo"

ThisBuild / scalaVersion := "2.12.21"

val scala3Lts = "3.4.3"

ThisBuild / crossScalaVersions := Seq(
  "2.11.12",
  scalaVersion.value,
  "2.13.18",
  scala3Lts
)

crossVersion := CrossVersion.binary

ThisBuild / credentials ++= sys.env.get("SONATYPE_USER").toSeq.map { user =>
  Credentials(
    "", // Empty realm credential - this one is actually used by Coursier!
    "central.sonatype.com",
    user,
    Publish.env("SONATYPE_PASS")
  )
}

ThisBuild / resolvers ++= Seq(
  "Central Testing repository" at "https://central.sonatype.com/api/v1/publisher/deployments/download",
  "Sonatype Snapshots" at "https://central.sonatype.com/repository/maven-snapshots/",
  Resolver.typesafeRepo("releases")
)

lazy val disabledIteratees = Def.setting[Boolean] {
  val v = scalaBinaryVersion.value

  v == "2.13" || v == "3"
}

lazy val akkaVer = Def.setting[String] {
  sys.env.get("AKKA_VERSION").getOrElse {
    if (scalaBinaryVersion.value == "3") "2.6.21"
    else if (scalaBinaryVersion.value == "2.11") "2.4.10"
    else "2.5.32"
  }
}

lazy val iteratees = project
  .in(file("iteratees"))
  .settings(
    name := "reactivemongo-iteratees",
    Compile / sources := {
      if (disabledIteratees.value) Nil
      else (Compile / sources).value
    },
    publishArtifact := !disabledIteratees.value,
    publish := (Def.taskDyn {
      val ver = scalaBinaryVersion.value
      val go = publish.value

      Def.task {
        if (!disabledIteratees.value) {
          go
        }
      }
    }).value,
    libraryDependencies ++= {
      val playVer = sys.env.get("ITERATEES_VERSION").getOrElse {
        if (scalaBinaryVersion.value == "2.11") "2.3.10"
        else "2.6.1"
      }

      if (!disabledIteratees.value) {
        val akkaTestDeps = Seq("actor", "slf4j")

        Dependencies.shared.value ++: ("com.typesafe.play" %% "play-iteratees" % playVer % Provided) +: (akkaTestDeps.map {
          n => "com.typesafe.akka" %% s"akka-$n" % akkaVer.value % Test
        })

      } else {
        Seq.empty
      }
    },
    // MiMa
    mimaPreviousArtifacts := {
      if (disabledIteratees.value) Set.empty
      else mimaPreviousArtifacts.value
    },
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
        dmm(
          s"${pkg}.PlayIterateesFlattenedCursor.responseEnumerator$$default$$1"
        ),
        dmm(
          s"${pkg}.PlayIterateesFlattenedCursor.responseEnumerator$$default$$2"
        )
      )
    },
    // Publish
    apiURL := Some(
      uri(s"https://reactivemongo.github.io/ReactiveMongo-Streaming/${Publish.majorVersion}/iteratees/api/")
    ),
    // Tests
    Test / fork := true
  )

lazy val `akka-stream` = project.in(file("akka-stream")).settings(
  name := "reactivemongo-akkastream",
  Compile / compile / scalacOptions ++= {
    if (scalaBinaryVersion.value == "2.11") {
      Seq.empty
    } else {
      Seq("-Wconf:cat=deprecation&msg=.*(fromFuture|UpdateBuilder).*:s")
    }
  },
  Test / compile / scalacOptions ++= {
    if (scalaBinaryVersion.value == "2.11") {
      Seq.empty
    } else {
      Seq("-Wconf:cat=deprecation&msg=.*(expectNoMessage|ActorMaterializer).*:s")
    }
  },
  // See https://github.com/scala/bug/issues/11880#issuecomment-583682673
  Test / scalacOptions ++= {
    if (scalaBinaryVersion.value != "3") {
      Seq("-no-specialization")
    } else {
      Seq.empty
    }
  },
  Test / sources := {
    if (scalaBinaryVersion.value == "3") {
      (Test / sources).value.filter { _.getName.indexOf("README-md") != -1 }
    } else {
      (Test / sources).value
    }
  },
  libraryDependencies ++= Dependencies.shared.value ++ Seq(
    "com.typesafe.akka" %% "akka-stream" % akkaVer.value,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVer.value % Test,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVer.value % Test
  ),
  libraryDependencies += "commons-codec" % "commons-codec" % "1.22.1" % Test,
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
  },
  // Publish
  apiURL := Some(
    uri(s"https://reactivemongo.github.io/ReactiveMongo-Streaming/${Publish.majorVersion}/akka-stream/api/")
  ),
  // Tests
  Test / fork := true
)

lazy val `pekko-stream` = project.in(file("pekko-stream")).settings(
  name := "reactivemongo-pekkostream",
  Common.usePekko := true,
  crossScalaVersions ~= {
    _.filterNot(v => v.startsWith("2.11"))
  },
  mimaPreviousArtifacts := Set.empty,
  Compile / compile / scalacOptions ++= {
    val v = scalaBinaryVersion.value

    Seq("-Wconf:cat=deprecation&msg=.*(fromFuture|UpdateBuilder).*:s")
  },
  Test / compile / scalacOptions ++= {
    Seq("-Wconf:cat=deprecation&msg=.*expectNoMessage.*:s")
  },
  // See https://github.com/scala/bug/issues/11880#issuecomment-583682673
  Test / scalacOptions ++= {
    if (scalaBinaryVersion.value != "3") {
      Seq("-no-specialization")
    } else {
      Seq.empty
    }
  },
  Test / sources := {
    if (scalaBinaryVersion.value == "3") {
      (Test / sources).value.filter { f => f.getName.indexOf("README-md") != -1 }
    } else {
      (Test / sources).value
    }
  },
  libraryDependencies ++= {
    val pekkoVer = "1.6.0"

    Dependencies.shared.value ++ Seq(
      "org.apache.pekko" %% "pekko-stream" % pekkoVer,
      "org.apache.pekko" %% "pekko-slf4j" % pekkoVer % Test,
      "org.apache.pekko" %% "pekko-stream-testkit" % pekkoVer % Test
    )
  },
  libraryDependencies += "commons-codec" % "commons-codec" % "1.15" % Test,
  // Publish
  apiURL := Some(
    uri(s"https://reactivemongo.github.io/ReactiveMongo-Streaming/${Publish.majorVersion}/pekko-stream/api/")
  ),
  // Tests
  Test / fork := true
)

lazy val streaming = (project in file("."))
  .settings(
    Seq(
      publish := ({}),
      publishTo := None,
      mimaPreviousArtifacts := Set.empty,
      mimaFailOnNoPrevious := false,
      libraryDependencies += (reactiveMongo % version.value % Provided)
        .exclude("com.typesafe.akka", "*")
    ) ++ Release.settings
  )
  .aggregate(iteratees, `akka-stream`, `pekko-stream`)
  .enablePlugins(ScalaUnidocPlugin)
  .disablePlugins(HighlightExtractorPlugin)
