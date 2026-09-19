package cairn.store.postgres

import cairn.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import zio.*
import zio.schema.Schema
import zio.test.*

import java.sql.DriverManager

/**
 * Crash recovery over a real Postgres: the reason this module exists (CLAUDE.md, Test strategy).
 * "Crash" here means the interpreter fiber is interrupted mid-graph and its whole connection pool
 * is closed; the resumed run gets a brand new pool, so nothing survives in the JVM except what
 * Postgres holds.
 *
 * WRITTEN, NOT YET RUN: needs a Docker daemon, which was not available when this was written. Run
 * before merging.
 */
object PostgresCrashRecoverySpec extends ZIOSpecDefault:

  private given intSchema: Schema[Int] = Schema.primitive[Int]

  private def counting(id: String, calls: Ref[Int]): Node.Effect[Any, Nothing, Int, Int] =
    Node.Effect(NodeId(id), i => calls.update(_ + 1).as(i * 2), intSchema)

  private def adminExec(c: PostgreSQLContainer, sql: String): Task[Unit] =
    ZIO.attemptBlocking {
      val conn = DriverManager.getConnection(c.jdbcUrl, c.username, c.password)
      try conn.createStatement().execute(sql)
      finally conn.close()
      ()
    }

  def spec = suite("crash recovery over Postgres")(
    test(
      "a run killed mid-graph and resumed through a brand new pool does not re-run committed nodes"
    ) {
      val runId = RunId("pg-kill-mid-seq")
      for
        c <- ZIO.service[PostgreSQLContainer]
        leftCalls <- Ref.make(0)
        rightCalls <- Ref.make(0)
        reachedRight <- Promise.make[Nothing, Unit]
        left = counting("left", leftCalls)
        hangingRight = Node.Effect[Any, Nothing, Int, Int](
          NodeId("right"),
          _ => rightCalls.update(_ + 1) *> reachedRight.succeed(()) *> ZIO.never,
          intSchema
        )
        finishingRight = Node.Effect[Any, Nothing, Int, Int](
          NodeId("right"),
          i => rightCalls.update(_ + 1).as(i + 1),
          intSchema
        )
        _ <- ZIO.scoped {
          for
            store <- PostgresTestSupport.openStore(c)
            fiber <- Interpreter
              .run(Node.Seq(NodeId("pipeline"), left, hangingRight), 10, runId)
              .provideEnvironment(ZEnvironment(store))
              .fork
            _ <- reachedRight.await
            _ <- fiber.interrupt
          yield ()
        }
        resumed <- ZIO.scoped {
          PostgresTestSupport
            .openStore(c)
            .flatMap(store =>
              Interpreter
                .run(Node.Seq(NodeId("pipeline"), left, finishingRight), 10, runId)
                .provideEnvironment(ZEnvironment(store))
            )
        }
        leftRuns <- leftCalls.get
        rightRuns <- rightCalls.get
      yield assertTrue(
        resumed == 21, // (10 * 2) + 1
        leftRuns == 1, // committed before the kill; replayed from Postgres, not re-run
        rightRuns == 2 // started before the kill, never committed; genuinely re-run
      )
    },
    test("a fully accepted loop replays body and accept from a brand new pool") {
      val runId = RunId("pg-loop-replay")
      for
        c <- ZIO.service[PostgreSQLContainer]
        bodyCalls <- Ref.make(0)
        acceptCalls <- Ref.make(0)
        propose = Node.Effect[Any, Nothing, (Int, Option[Node.Loop.Feedback]), Int](
          NodeId("propose"),
          { case (i, _) => bodyCalls.update(_ + 1).as(i) },
          intSchema
        )
        accept = (_: Int) => acceptCalls.update(_ + 1).as(true)
        node = Node.Loop(NodeId("propose-loop"), propose, accept, max = 3)
        run = ZIO.scoped(
          PostgresTestSupport
            .openStore(c)
            .flatMap(store =>
              Interpreter.run(node, 7, runId).provideEnvironment(ZEnvironment(store))
            )
        )
        first <- run
        second <- run
        bodyRuns <- bodyCalls.get
        acceptRuns <- acceptCalls.get
      yield assertTrue(first == 7, second == 7, bodyRuns == 1, acceptRuns == 1)
    },
    test("a stored value that does not decode surfaces as DecodeFailed, not an exception") {
      val runId = RunId("pg-corrupt")
      for
        c <- ZIO.service[PostgreSQLContainer]
        _ <- ZIO.scoped(PostgresTestSupport.openStore(c)).unit // creates the tables
        _ <- adminExec(
          c,
          "INSERT INTO cairn_checkpoint (run_id, node_id, attempt, value, committed_at) " +
            "VALUES ('pg-corrupt', 'n', 0, '\"not a dynamic value\"'::json, now())"
        )
        result <- ZIO
          .scoped(
            PostgresTestSupport.openStore(c).flatMap(_.get(runId, NodeId("n"), Attempt.first))
          )
          .either
      yield assertTrue(
        result.left.exists {
          case StoreError.DecodeFailed(rid, nid, _) => rid == runId && nid == NodeId("n")
          case _ => false
        }
      )
    },
    test("many stores starting at once against an empty database do not race on migration") {
      for
        c <- ZIO.service[PostgreSQLContainer]
        _ <- adminExec(c, "CREATE DATABASE migrate_race")
        config = PostgresTestSupport
          .configFor(c)
          .copy(jdbcUrl = c.jdbcUrl.replace(s"/${c.databaseName}", "/migrate_race"))
        results <- ZIO.foreachPar(1 to 8)(_ => ZIO.scoped(PostgresStore.layer(config).build).either)
      yield assertTrue(results.forall(_.isRight))
    }
  ).provideShared(PostgresTestSupport.container)
