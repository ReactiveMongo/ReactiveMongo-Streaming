resolvers ++= Seq(
  "Tatami Releases" at "https://raw.github.com/cchantep/tatami/master/releases",
  "Tatami Snapshots" at "https://raw.github.com/cchantep/tatami/master/snapshots",
  Resolver.mavenLocal
)

addSbtPlugin("com.github.sbt" % "sbt-unidoc" % "0.6.1")

addDependencyTreePlugin

addSbtPlugin("cchantep" % "sbt-scaladoc-compiler" % "0.8")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")

addSbtPlugin("cchantep" % "sbt-hl-compiler" % "0.12")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.1.6")

addSbtPlugin("com.github.sbt" % "sbt-release" % "1.5.0")

addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.1")

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.7")
