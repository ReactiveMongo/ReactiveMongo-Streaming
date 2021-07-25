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
      val v = (ThisBuild / version).value
      val suffix = {
        if (useShaded.value) "" // default ~> no suffix
        else "-noshaded"
      }

      v.span(_ != '-') match {
        case (a, b) => s"${a}${suffix}${b}"
      }
    },
    libraryDependencies ++= {
      val v = (ThisBuild / version).value
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
        "org.specs2" %% _ % "4.10.6" % Test) ++ Seq(
        Dependencies.slf4jSimple % Test))
    }
  ) ++ Publish.settings ++ (
    Publish.mimaSettings ++ Release.settings)
}

object Dependencies {
  val reactiveMongo = "org.reactivemongo" %% "reactivemongo"

  val slf4jSimple = "org.slf4j" % "slf4j-simple" % "1.7.32"
}
