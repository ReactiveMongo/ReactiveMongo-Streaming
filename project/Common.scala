import sbt.Keys._
import sbt._

object Common {
  val settings = Seq(
    scalacOptions in Compile ++= Seq(
      "-unchecked", "-deprecation",
      "-Ywarn-unused",
      "-Ywarn-unused-import",
      "-Ywarn-value-discard",
      "-Ywarn-numeric-widen",
      "-Ywarn-infer-any",
      "-Ywarn-dead-code"),
    scalacOptions in Compile ++= {
      if (scalaVersion.value startsWith "2.10.") Nil
      else Seq("-Ywarn-unused", "-Xlint")
    },
    autoAPIMappings := true,
    scalacOptions in (Compile, doc) := Seq(
      "-Ywarn-dead-code", "-Ywarn-unused-import", "-unchecked", "-deprecation",
      /*"-diagrams", */ "-implicits", "-skip-packages", "samples") ++
      Opts.doc.title("ReactiveMongo Streaming API") ++
      Opts.doc.version(Release.major.value),
    libraryDependencies ++= Seq(
      Dependencies.reactiveMongo % version.value % "provided") ++ Seq(
        "specs2-core", "specs2-junit").map(
          "org.specs2" %% _ % "3.9.4" % Test) ++ Seq(
            Dependencies.slf4jSimple % Test)
  ) ++ Format.settings ++ Publish.settings ++ (
      Scapegoat.settings ++ Publish.mimaSettings ++ Release.settings)

  val testCleanup: ClassLoader => Unit = { cl =>
    import scala.language.reflectiveCalls

    val c = cl.loadClass("Common$")
    type M = { def close(): Unit }
    val m: M = c.getField("MODULE$").get(null).asInstanceOf[M]

    m.close()
  }
}

object Format {
  import com.typesafe.sbt.SbtScalariform, SbtScalariform._

  val settings = {
    import scalariform.formatter.preferences._
    autoImport.scalariformSettings(autoformat = true) ++ Seq(
      ScalariformKeys.preferences := ScalariformKeys.preferences.value.
        setPreference(AlignParameters, false).
        setPreference(AlignSingleLineCaseStatements, true).
        setPreference(CompactControlReadability, false).
        setPreference(CompactStringConcatenation, false).
        setPreference(DoubleIndentConstructorArguments, true).
        setPreference(FormatXml, true).
        setPreference(IndentLocalDefs, false).
        setPreference(IndentPackageBlocks, true).
        setPreference(IndentSpaces, 2).
        setPreference(MultilineScaladocCommentsStartOnFirstLine, false).
        setPreference(PreserveSpaceBeforeArguments, false).
        setPreference(DanglingCloseParenthesis, Preserve).
        setPreference(RewriteArrowSymbols, false).
        setPreference(SpaceBeforeColon, false).
        setPreference(SpaceInsideBrackets, false).
        setPreference(SpacesAroundMultiImports, true).
        setPreference(SpacesWithinPatternBinders, true)
    )
  }
}

object Publish {
  import com.typesafe.tools.mima.plugin.MimaPlugin.mimaDefaultSettings
  import com.typesafe.tools.mima.plugin.MimaKeys.mimaPreviousArtifacts

  @inline def env(n: String): String = sys.env.get(n).getOrElse(n)

  val previousVersion = "0.12.0"
  val majorVersion = "0.12"
  lazy val repoName = env("PUBLISH_REPO_NAME")
  lazy val repoUrl = env("PUBLISH_REPO_URL")

  val mimaSettings = mimaDefaultSettings ++ Seq(
    mimaPreviousArtifacts := {
      if (!scalaVersion.value.startsWith("2.12")) {
        Set(organization.value %% moduleName.value % previousVersion)
      } else {
        Set.empty
      }
    }
  )

  val settings = Seq(
    publishMavenStyle := true,
    publishArtifact in Test := false,
    pomIncludeRepository := { _ => false },
    licenses := Seq("Apache 2.0" ->
      url("http://www.apache.org/licenses/LICENSE-2.0")),
    homepage := Some(url("http://reactivemongo.org")),
    autoAPIMappings := true,
    pomExtra := (
      <scm>
        <url>git://github.com/ReactiveMongo/ReactiveMongo-Streaming.git</url>
        <connection>scm:git://github.com/ReactiveMongo/ReactiveMongo-Streaming.git</connection>
      </scm>
      <developers>
        <developer>
          <id>cchantep</id>
          <name>Cedric Chantepie</name>
          <url>https://github.com/cchantep/</url>
        </developer>
      </developers>),
    publishTo := Some(repoUrl).map(repoName at _),
    credentials += Credentials(repoName, env("PUBLISH_REPO_ID"),
      env("PUBLISH_USER"), env("PUBLISH_PASS"))
  )
}

object Dependencies {
  val reactiveMongo = "org.reactivemongo" %% "reactivemongo"

  val slf4jSimple = "org.slf4j" % "slf4j-simple" % "1.7.13"
}
