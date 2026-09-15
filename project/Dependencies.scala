import sbt._

/** Versions pinned per CLAUDE.md, checked against Maven Central on 2026-09-15.
  * Do not bump without checking the notes in CLAUDE.md first.
  */
object Dependencies {

  val scala3Version = "3.9.0"

  private val zioVersion       = "2.1.26"
  private val zioSchemaVersion = "1.8.7"
  private val sttpVersion      = "4.0.26"
  private val sttpOpenAIVersion         = "0.3.10" // resolve-latest, checked 2026-09-15
  private val dbos4sVersion             = "0.1.0"  // resolve-latest, checked 2026-09-15
  private val testcontainersScalaVersion = "0.44.1" // resolve-latest, checked 2026-09-15
  private val hikariCPVersion   = "7.1.0"
  private val postgresqlVersion = "42.7.13"

  val zio        = "dev.zio" %% "zio"         % zioVersion
  val zioStreams = "dev.zio" %% "zio-streams" % zioVersion
  val zioTest        = "dev.zio" %% "zio-test"          % zioVersion % Test
  val zioTestSbt     = "dev.zio" %% "zio-test-sbt"      % zioVersion % Test

  val zioSchema     = "dev.zio" %% "zio-schema"      % zioSchemaVersion
  val zioSchemaJson = "dev.zio" %% "zio-schema-json" % zioSchemaVersion

  // core: zio + zio-schema only (invariant #2 in CLAUDE.md — nothing else, ever, without asking).
  val core: Seq[ModuleID] = Seq(zio, zioSchema, zioTest, zioTestSbt)

  val sttpClient   = "com.softwaremill.sttp.client4" %% "core" % sttpVersion
  val sttpClientZio = "com.softwaremill.sttp.client4" %% "zio" % sttpVersion
  val sttpOpenAI    = "com.softwaremill.sttp.openai"  %% "core" % sttpOpenAIVersion

  val llm: Seq[ModuleID] =
    Seq(sttpClient, sttpClientZio, sttpOpenAI, zioSchemaJson, zioTest, zioTestSbt)

  val storeMemory: Seq[ModuleID] = Seq(zioTest, zioTestSbt)

  val hikariCP   = "com.zaxxer"    % "HikariCP"   % hikariCPVersion
  val postgresql = "org.postgresql" % "postgresql" % postgresqlVersion
  val testcontainersScalaCore =
    "com.dimafeng" %% "testcontainers-scala-core" % testcontainersScalaVersion % Test
  val testcontainersScalaPostgres =
    "com.dimafeng" %% "testcontainers-scala-postgresql" % testcontainersScalaVersion % Test

  val storePostgres: Seq[ModuleID] =
    Seq(hikariCP, postgresql, zioTest, zioTestSbt, testcontainersScalaCore, testcontainersScalaPostgres)

  // dbos4s artifact ids embed the DBOS transact protocol version they target (transact0.9).
  val dbos4s = "xyz.matthieucourt" %% "dbos4s-transact0.9" % dbos4sVersion

  val storeDbos: Seq[ModuleID] = Seq(dbos4s, zioTest, zioTestSbt)

  val testkit: Seq[ModuleID] = Seq(zioTest, zioTestSbt)

  val examples: Seq[ModuleID] = Seq(zioTest, zioTestSbt)
}
