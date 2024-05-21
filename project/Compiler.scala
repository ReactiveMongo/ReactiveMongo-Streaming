import sbt.Keys._
import sbt._

object Compiler {

  private def unmanaged(ver: String, base: File): Seq[File] =
    CrossVersion.partialVersion(ver) match {
      case Some((2, 13)) =>
        Seq(base / "scala-2.13+")

      case _ =>
        Seq(base / "scala-2.13-")

    }

  lazy val settings = Seq(
    Compile / unmanagedSourceDirectories ++= {
      unmanaged(scalaVersion.value, (Compile / sourceDirectory).value)
    },
    scalacOptions ++= Seq(
      "-encoding",
      "UTF-8",
      "-unchecked",
      "-deprecation",
      "-feature",
      "-language:higherKinds"
    ),
    scalacOptions ++= {
      if (scalaBinaryVersion.value != "2.11") {
        Seq("-Xfatal-warnings")
      } else {
        Seq.empty
      }
    },
    scalacOptions ++= {
      if (scalaBinaryVersion.value startsWith "2.") {
        Seq(
          "-Xlint",
          "-g:vars"
        )
      } else Seq.empty
    },
    scalacOptions ++= {
      val sv = scalaBinaryVersion.value

      if (sv == "2.12") {
        Seq(
          "-target:jvm-1.8",
          "-Xmax-classfile-name",
          "128",
          "-Ywarn-numeric-widen",
          "-Ywarn-dead-code",
          "-Ywarn-value-discard",
          "-Ywarn-infer-any",
          "-Ywarn-unused",
          "-Ywarn-unused-import",
          "-Xlint:missing-interpolator",
          "-Ywarn-macros:after"
        )
      } else if (sv == "2.11") {
        Seq(
          "-target:jvm-1.8",
          "-Xmax-classfile-name",
          "128",
          "-Yopt:_",
          "-Ydead-code",
          "-Yclosure-elim",
          "-Yconst-opt"
        )
      } else if (sv == "2.13") {
        Seq(
          "-release",
          "8",
          "-explaintypes",
          "-Werror",
          "-Wnumeric-widen",
          "-Wdead-code",
          "-Wvalue-discard",
          "-Wextra-implicit",
          "-Wmacros:after",
          "-Wunused"
        )
      } else {
        Seq("-release", "8", "-Wunused:all", "-language:implicitConversions")
      }
    },
    Compile / console / scalacOptions ~= {
      _.filterNot(o => o.startsWith("-X") || o.startsWith("-Y"))
    },
    Test / scalacOptions ~= {
      _.filterNot(_ == "-Xfatal-warnings")
    },
    Test / scalacOptions ++= {
      if (scalaBinaryVersion.value == "2.11") {
        Seq.empty
      } else {
        Seq(
          "-Wconf:src=.*test/.*&msg=.*type\\ was\\ inferred.*(Any|Object).*:s"
        )
      }
    },
    Compile / doc / scalacOptions ~= {
      _.filterNot(excludeOpt)
    },
    Compile / console / scalacOptions ~= {
      _.filterNot(excludeOpt)
    },
    Test / console / scalacOptions ~= {
      _.filterNot(excludeOpt)
    },
    Test / console / scalacOptions += "-Yrepl-class-based"
  )

  private lazy val excludeOpt: String => Boolean = { opt =>
    (opt.startsWith("-X") && opt != "-Xmax-classfile-name") ||
    opt.startsWith("-Y") || opt.startsWith("-W") ||
    opt.startsWith("-P:semanticdb")
  }
}
