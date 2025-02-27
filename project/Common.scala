import sbt.Keys._
import sbt._
import sbt.plugins.JvmPlugin

import com.typesafe.tools.mima.plugin.MimaKeys.mimaFailOnNoPrevious

object Common extends AutoPlugin {
  override def trigger = allRequirements
  override def requires = JvmPlugin

  val useShaded = settingKey[Boolean](
    "Use ReactiveMongo-Shaded (see system property 'reactivemongo.shaded')"
  )

  val usePekko = settingKey[Boolean]("Use ReactiveMongo-Actors-Pekko")

  val driverVersion = settingKey[String]("Version of the driver dependency")

  val bsonVersion = settingKey[String]("Version of the BSON dependency")

  override def projectSettings = Compiler.settings ++ Seq(
    mimaFailOnNoPrevious := false,
    useShaded := sys.env.get("REACTIVEMONGO_SHADED").fold(true)(_.toBoolean),
    usePekko := false,
    bsonVersion := {
      val ver = (ThisBuild / version).value
      val suffix = {
        if (useShaded.value) "" // default ~> no suffix
        else "noshaded"
      }

      if (suffix.isEmpty) {
        ver
      } else {
        ver.span(_ != '-') match {
          case (_, "") => s"${ver}-${suffix}"

          case (a, b) => s"${a}-${suffix}.${b stripPrefix "-"}"
        }
      }
    },
    version := bsonVersion.value,
    driverVersion := {
      val ver = bsonVersion.value

      val suffix = {
        if (usePekko.value) "pekko"
        else ""
      }

      if (suffix.isEmpty) {
        ver
      } else {
        ver.span(_ != '-') match {
          case (_, "") => s"${ver}-${suffix}"

          case (a, b) => s"${a}-${suffix}.${b stripPrefix "-"}"
        }
      }
    }
  ) ++ Publish.settings ++ (Publish.mimaSettings ++ Release.settings)
}

object Dependencies {
  val reactiveMongo = "org.reactivemongo" %% "reactivemongo"

  val slf4jSimple = "org.slf4j" % "slf4j-simple" % "2.0.17"

  val shared = Def.setting[Seq[ModuleID]] {
    val v = (ThisBuild / version).value
    val ver = Common.driverVersion.value
    val driver = Dependencies.reactiveMongo % ver % Provided

    val rmDeps = {
      if (Common.useShaded.value) {
        Seq(driver)
      } else {
        Seq(
          driver,
          "org.reactivemongo" %% "reactivemongo-alias" % v % Provided,
          "org.reactivemongo" %% "reactivemongo-bson-api" % Common.bsonVersion.value % Provided,
          "io.netty" % "netty-handler" % "4.1.43.Final" % Provided
        )
      }
    }

    val specVer = if (scalaBinaryVersion.value == "3") "5.5.2" else "4.10.6"

    rmDeps.map(_.exclude("com.typesafe.akka", "*")) ++: (Seq(
      "specs2-core",
      "specs2-junit"
    ).map(n => "org.specs2" %% n % specVer % Test) ++: Seq(
      Dependencies.slf4jSimple % Test
    ))
  }
}
