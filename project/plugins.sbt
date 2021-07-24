resolvers ++= Seq(
  "Tatami Releases" at "https://raw.github.com/cchantep/tatami/master/releases")

addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.8.3")

addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.4.3")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.10.0-RC1")

addSbtPlugin("cchantep" % "sbt-scaladoc-compiler" % "0.2")

addSbtPlugin("cchantep" % "sbt-hl-compiler" % "0.8")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.9.2")

addSbtPlugin("com.github.sbt" % "sbt-findbugs" % "2.0.0")

addSbtPlugin("com.github.sbt" % "sbt-release" % "1.1.0")

addSbtPlugin("com.sksamuel.scapegoat" %% "sbt-scapegoat" % "1.1.0")

addSbtPlugin("com.dwijnand" % "sbt-dynver" % "4.1.1")
