import sbt.Keys._
import sbt._
import sbt.plugins.JvmPlugin

object Publish {
  import com.typesafe.tools.mima.core._, ProblemFilters._
  import com.typesafe.tools.mima.plugin.MimaPlugin.mimaDefaultSettings
  import com.typesafe.tools.mima.plugin.MimaKeys.{
    mimaPreviousArtifacts,
    mimaBinaryIssueFilters
  }

  @inline def env(n: String): String = sys.env.get(n).getOrElse(n)

  val previousVersion = "1.0.0"
  val majorVersion = "1.0"
  lazy val repoName = env("PUBLISH_REPO_NAME")
  lazy val repoUrl = env("PUBLISH_REPO_URL")

  val mimaSettings = Seq(
    mimaPreviousArtifacts := {
      val v = scalaBinaryVersion.value

      if (version.value != previousVersion) {
        if (v == "2.13" || v == "3") Set.empty[ModuleID]
        else {
          Set(organization.value %% moduleName.value % previousVersion)
        }
      } else {
        Set.empty[ModuleID]
      }
    },
    mimaBinaryIssueFilters ++= Seq.empty
  )

  val settings = Seq(
    Compile / doc / scalacOptions ++= {
      if (scalaBinaryVersion.value startsWith "2.") {
        Seq( /*"-diagrams", */ "-implicits", "-skip-packages", "samples")
      } else {
        Seq("-skip-by-id:samples")
      }
    },
    Compile / doc / scalacOptions ++= Opts.doc
      .title("ReactiveMongo Streaming API") ++
      Opts.doc.version(Release.major.value),
    publishMavenStyle := true,
    Test / publishArtifact := false,
    pomIncludeRepository := { _ => false },
    licenses := Seq(
      "Apache 2.0" ->
        url("http://www.apache.org/licenses/LICENSE-2.0")
    ),
    homepage := Some(url("http://reactivemongo.org")),
    autoAPIMappings := true,
    pomExtra := (<scm>
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
    credentials += Credentials(
      repoName,
      env("PUBLISH_REPO_ID"),
      env("PUBLISH_USER"),
      env("PUBLISH_PASS")
    )
  )
}
