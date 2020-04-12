import sbt.Keys._
import sbt._
import sbt.plugins.JvmPlugin

import com.typesafe.tools.mima.plugin.MimaKeys.mimaFailOnNoPrevious

object Common extends AutoPlugin {
  override def trigger = allRequirements
  override def requires = JvmPlugin

  val useShaded = settingKey[Boolean](
    "Use ReactiveMongo-Shaded (see system property 'reactivemongo.shaded')")

  val driverVersion = settingKey[String]("Version of the driver dependency")

  override def projectSettings = Compiler.settings ++ Seq(
    mimaFailOnNoPrevious := false,
    useShaded := sys.env.get("REACTIVEMONGO_SHADED").fold(true)(_.toBoolean),
    driverVersion := {
      val v = (version in ThisBuild).value
      val suffix = {
        if (useShaded.value) "" // default ~> no suffix
        else "-noshaded"
      }

      v.span(_ != '-') match {
        case (a, b) => s"${a}${suffix}${b}"
      }
    },
    libraryDependencies ++= {
      val v = (version in ThisBuild).value
      val ver = driverVersion.value
      val driver = Dependencies.reactiveMongo % ver % Provided

      val rmDeps = {
        if (useShaded.value) {
          Seq(driver)
        } else {
          Seq(
            driver,
            "org.reactivemongo" %% "reactivemongo-alias" % v % Provided,
            "org.reactivemongo" %% "reactivemongo-bson-api" % ver % Provided,
            "io.netty" % "netty-handler" % "4.1.43.Final" % Provided)
        }
      }

      rmDeps ++ (Seq(
        "specs2-core", "specs2-junit").map(
        "org.specs2" %% _ % "4.9.3" % Test) ++ Seq(
        Dependencies.slf4jSimple % Test))
    }
  ) ++ Format.settings ++ Publish.settings ++ (
    Publish.mimaSettings ++ Release.settings)

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
    projectSettings ++ Seq(
      autoImport.scalariformAutoformat := true,
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
  import com.typesafe.tools.mima.core._, ProblemFilters._
  import com.typesafe.tools.mima.plugin.MimaPlugin.mimaDefaultSettings
  import com.typesafe.tools.mima.plugin.MimaKeys.{
    mimaPreviousArtifacts, mimaBinaryIssueFilters
  }

  @inline def env(n: String): String = sys.env.get(n).getOrElse(n)

  val previousVersion = "1.0.0-rc.1-SNAPSHOT"
  val majorVersion = "1.0"
  lazy val repoName = env("PUBLISH_REPO_NAME")
  lazy val repoUrl = env("PUBLISH_REPO_URL")

  val mimaSettings = Seq(
    mimaPreviousArtifacts := {
      if (version.value != previousVersion) {
        Set(organization.value %% moduleName.value % previousVersion)
      } else {
        Set.empty
      }
    },
    mimaBinaryIssueFilters ++= Seq.empty
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

  val slf4jSimple = "org.slf4j" % "slf4j-simple" % "1.7.30"
}
