import sbt.Keys._
import sbt._

object Compiler {
  lazy val settings = Seq(
    scalacOptions ++= Seq(
      "-encoding", "UTF-8", "-target:jvm-1.8",
      "-unchecked",
      "-deprecation",
      "-feature",
      "-Xfatal-warnings",
      "-Xlint",
      "-g:vars"),
    scalacOptions ++= {
      if (!scalaVersion.value.startsWith("2.13.")) {
        Seq(
          "-Ywarn-numeric-widen",
          "-Ywarn-dead-code",
          "-Ywarn-value-discard",
          "-Ywarn-infer-any",
          "-Ywarn-unused",
          "-Ywarn-unused-import")
      } else Nil
    },
    scalacOptions += { // Silencer
      "-P:silencer:globalFilters=Response\\ in\\ package\\ protocol\\ is\\ deprecated;killCursor;Use\\ \\`find\\`\\ with\\ optional\\ \\`projection\\`"
    },
    scalacOptions in Compile ++= {
      if (!scalaVersion.value.startsWith("2.11.")) Nil
      else Seq(
        "-Yconst-opt",
        "-Yclosure-elim",
        "-Ydead-code",
        "-Yopt:_"
      )
    },
    scalacOptions in (Compile, doc) := (scalacOptions in Test).value,
    scalacOptions in (Compile, console) ~= {
      _.filterNot { opt =>
        opt.startsWith("-X") || opt.startsWith("-Y") || opt.startsWith("-P")
      }
    },
    scalacOptions in (Test, console) ~= {
      _.filterNot { opt =>
        opt.startsWith("-X") || opt.startsWith("-Y") || opt.startsWith("-P")
      }
    },
    scalacOptions in (Compile, doc) ++= Seq(
      "-Ywarn-dead-code", "-Ywarn-unused-import", "-unchecked", "-deprecation",
      /*"-diagrams", */"-implicits", "-skip-packages", "samples") ++
      Opts.doc.title("ReactiveMongo Streaming API") ++
      Opts.doc.version(Release.major.value)
  )
}
