addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.11.2")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.6")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.1")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.9.2")

// sbt-mima-plugin is intentionally not included: no sbt 2 release yet (open PR).
// Binary-compatibility checks are deferred past 1.0 regardless; see CLAUDE.md.
