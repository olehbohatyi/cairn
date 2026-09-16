import Dependencies._

ThisBuild / scalaVersion := scala3Version
ThisBuild / organization := "io.github.olehbohatyi"
ThisBuild / homepage := Some(url("https://github.com/olehbohatyi/cairn"))
ThisBuild / licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / developers := List(
  Developer("olehbohatyi", "Oleh Bohatyi", "olehbohatyi@gmail.com", url("https://github.com/olehbohatyi"))
)
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

// No sbt-mima-plugin: no sbt 2 release yet, and binary-compat checks are deferred
// past 1.0 regardless (see CLAUDE.md). Revisit both when the plugin ships for sbt 2.

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-release", "17",
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Wunused:all"
  ),
  Test / fork := true,
  Test / parallelExecution := true,
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
)

lazy val root = project
  .in(file("."))
  .settings(commonSettings)
  .settings(
    name := "cairn",
    publish / skip := true
  )
  .aggregate(core, llm, storeMemory, storePostgres, storeDbos, testkit, examples, docs)

lazy val core = project
  .in(file("core"))
  .settings(commonSettings)
  .settings(
    name := "cairn-core",
    libraryDependencies ++= Dependencies.core
  )

lazy val llm = project
  .in(file("llm"))
  .dependsOn(core, storeMemory % Test)
  .settings(commonSettings)
  .settings(
    name := "cairn-llm",
    libraryDependencies ++= Dependencies.llm
  )

lazy val storeMemory = project
  .in(file("store-memory"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "cairn-store-memory",
    libraryDependencies ++= Dependencies.storeMemory
  )

lazy val storePostgres = project
  .in(file("store-postgres"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "cairn-store-postgres",
    libraryDependencies ++= Dependencies.storePostgres
  )

lazy val storeDbos = project
  .in(file("store-dbos"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "cairn-store-dbos",
    libraryDependencies ++= Dependencies.storeDbos
  )

lazy val testkit = project
  .in(file("testkit"))
  .dependsOn(core, llm)
  .settings(commonSettings)
  .settings(
    name := "cairn-testkit",
    libraryDependencies ++= Dependencies.testkit
  )

lazy val examples = project
  .in(file("examples"))
  .dependsOn(core, llm, storeMemory, storePostgres, storeDbos, testkit % Test)
  .settings(commonSettings)
  .settings(
    name := "cairn-examples",
    publish / skip := true,
    libraryDependencies ++= Dependencies.examples
  )

lazy val docs = project
  .in(file("cairn-docs"))
  .dependsOn(core)
  .enablePlugins(MdocPlugin)
  .settings(
    name := "cairn-docs",
    publish / skip := true,
    mdocIn := (ThisBuild / baseDirectory).value / "docs",
    mdocOut := target.value / "mdoc"
  )

// See CLAUDE.md "Commands". testAll needs Docker (store-postgres's
// testcontainers specs); testFast is offline and skips that module.
addCommandAlias("lint", ";scalafmtCheckAll;scalafixAll --check")
addCommandAlias("testAll", ";test")
addCommandAlias(
  "testFast",
  ";core/test;llm/test;storeMemory/test;storeDbos/test;testkit/test;examples/test"
)
