resolvers ++= Seq(
  "Tatami Releases" at "https://raw.github.com/cchantep/tatami/master/releases")

addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.8.2")

addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.4.1")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.9.0")

addSbtPlugin("cchantep" % "sbt-hl-compiler" % "0.5")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.1.18")

addSbtPlugin("com.sksamuel.scapegoat" %% "sbt-scapegoat" % "1.0.5")

addSbtPlugin("de.johoop" % "findbugs4sbt" % "1.4.0")

addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.7")
