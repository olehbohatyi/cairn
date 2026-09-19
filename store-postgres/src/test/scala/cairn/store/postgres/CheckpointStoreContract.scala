package cairn.store.postgres

import cairn.*
import zio.schema.{DeriveSchema, Schema}
import zio.test.*
import zio.{test as _, *}

import java.time.Instant

/**
 * What every [[CheckpointStore]] must do, written once against the trait. `InMemoryContractSpec`
 * runs it against `InMemoryStore` (offline, and how the assertions themselves were validated);
 * `PostgresContractSpec` runs it against a real Postgres under testcontainers (needs Docker).
 *
 * Tests share one store, so each uses its own `RunId`. No network calls - CLAUDE.md invariant #4.
 */
object CheckpointStoreContract:

  private given intSchema: Schema[Int] = Schema.primitive[Int]

  final case class Pair(a: Int, b: Option[String])
  private given pairSchema: Schema[Pair] = DeriveSchema.gen[Pair]

  // Microsecond precision: Postgres TIMESTAMPTZ keeps no more, so equality holds on every store.
  private val t0 = Instant.parse("2026-09-19T10:15:30.123456Z")

  private def checkpoint(
      runId: RunId,
      nodeId: String,
      attempt: Attempt = Attempt.first,
      value: Int = 1,
      at: Instant = t0
  ): Checkpoint =
    Checkpoint(runId, NodeId(nodeId), attempt, intSchema.toDynamic(value), None, None, at)

  private def counting(id: String, calls: Ref[Int]): Node.Effect[Any, Nothing, Int, Int] =
    Node.Effect(NodeId(id), i => calls.update(_ + 1).as(i * 2), intSchema)

  private def withStore[A](f: CheckpointStore => ZIO[CheckpointStore, Any, A]) =
    ZIO.serviceWithZIO[CheckpointStore](store => f(store))

  val tests: Spec[CheckpointStore, Any] = suite("CheckpointStore contract")(
    suite("store level")(
      test("a committed checkpoint reads back with value, cost, tokens and timestamp intact") {
        val runId = RunId("c-roundtrip")
        val cp = checkpoint(runId, "n", value = 42).copy(
          cost = Some(Money(125, "USD")),
          tokens = Some(TokenCount(10, 20))
        )
        withStore { store =>
          for
            _ <- store.commit(cp)
            got <- store.get(runId, cp.nodeId, cp.attempt)
          yield assertTrue(
            got.map(_.runId) == Some(runId),
            got.map(_.nodeId) == Some(cp.nodeId),
            got.map(_.attempt) == Some(cp.attempt),
            got.flatMap(g => intSchema.fromDynamic(g.value).toOption) == Some(42),
            got.flatMap(_.cost) == Some(Money(125, "USD")),
            got.flatMap(_.tokens) == Some(TokenCount(10, 20)),
            got.map(_.committedAt) == Some(t0)
          )
        }
      },
      test("a checkpoint with no cost and no tokens reads back with neither") {
        val runId = RunId("c-no-cost")
        withStore { store =>
          for
            _ <- store.commit(checkpoint(runId, "n"))
            got <- store.get(runId, NodeId("n"), Attempt.first)
          yield assertTrue(got.exists(g => g.cost.isEmpty && g.tokens.isEmpty))
        }
      },
      test("a structured value survives the round trip") {
        val runId = RunId("c-structured")
        val value = Pair(7, Some("seven"))
        val cp = checkpoint(runId, "n").copy(value = pairSchema.toDynamic(value))
        withStore { store =>
          for
            _ <- store.commit(cp)
            got <- store.get(runId, cp.nodeId, cp.attempt)
          yield assertTrue(
            got.flatMap(g => pairSchema.fromDynamic(g.value).toOption) == Some(value)
          )
        }
      },
      test("a key that was never committed is None") {
        withStore(
          _.get(RunId("c-missing"), NodeId("n"), Attempt.first).map(r => assertTrue(r.isEmpty))
        )
      },
      test("committing the same key twice fails AlreadyCommitted and keeps the first value") {
        val runId = RunId("c-write-once")
        withStore { store =>
          for
            _ <- store.commit(checkpoint(runId, "n", value = 1))
            second <- store.commit(checkpoint(runId, "n", value = 999)).either
            got <- store.get(runId, NodeId("n"), Attempt.first)
          yield assertTrue(
            second == Left(StoreError.AlreadyCommitted(runId, NodeId("n"), Attempt.first)),
            got.flatMap(g => intSchema.fromDynamic(g.value).toOption) == Some(1)
          )
        }
      },
      test("attempt and run id are part of the key") {
        val runA = RunId("c-key-a")
        val runB = RunId("c-key-b")
        withStore { store =>
          for
            _ <- store.commit(checkpoint(runA, "n", Attempt(0), value = 1))
            _ <- store.commit(checkpoint(runA, "n", Attempt(1), value = 2))
            _ <- store.commit(checkpoint(runB, "n", Attempt(0), value = 3))
            a0 <- store.get(runA, NodeId("n"), Attempt(0))
            a1 <- store.get(runA, NodeId("n"), Attempt(1))
            b0 <- store.get(runB, NodeId("n"), Attempt(0))
            decode = (c: Option[Checkpoint]) =>
              c.flatMap(x => intSchema.fromDynamic(x.value).toOption)
          yield assertTrue(decode(a0) == Some(1), decode(a1) == Some(2), decode(b0) == Some(3))
        }
      },
      test("a node id containing path separators round trips unchanged") {
        val runId = RunId("c-path-id")
        val id = "fan#0/v/leaf"
        withStore { store =>
          for
            _ <- store.commit(checkpoint(runId, id))
            got <- store.get(runId, NodeId(id), Attempt.first)
          yield assertTrue(got.map(_.nodeId) == Some(NodeId(id)))
        }
      },
      test("concurrent commits of one key: exactly one wins, every loser sees AlreadyCommitted") {
        val runId = RunId("c-concurrent")
        withStore { store =>
          for results <- ZIO.foreachPar(1 to 16)(i =>
              store.commit(checkpoint(runId, "n", value = i)).either
            )
          yield assertTrue(
            results.count(_.isRight) == 1,
            results.count(
              _ == Left(StoreError.AlreadyCommitted(runId, NodeId("n"), Attempt.first))
            ) == 15
          )
        }
      },
      test("list returns this run's checkpoints in commit-time order, and no other run's") {
        val runId = RunId("c-list")
        withStore { store =>
          for
            _ <- store.commit(checkpoint(runId, "third", at = t0.plusSeconds(2)))
            _ <- store.commit(checkpoint(runId, "first", at = t0))
            _ <- store.commit(checkpoint(runId, "second", at = t0.plusSeconds(1)))
            _ <- store.commit(checkpoint(RunId("c-list-other"), "elsewhere"))
            listed <- store.list(runId)
          yield assertTrue(listed.map(_.nodeId.value) == Chunk("first", "second", "third"))
        }
      },
      test("delete removes one run's checkpoints and attempts and leaves other runs alone") {
        val gone = RunId("c-delete-gone")
        val kept = RunId("c-delete-kept")
        val record = AttemptRecord(
          gone,
          NodeId("n"),
          Attempt.first,
          AttemptRecord.Outcome.Failed,
          None,
          None,
          t0
        )
        withStore { store =>
          for
            _ <- store.commit(checkpoint(gone, "n"))
            _ <- store.recordAttempt(record)
            _ <- store.commit(checkpoint(kept, "n"))
            _ <- store.recordAttempt(record.copy(runId = kept))
            _ <- store.delete(gone)
            goneCheckpoints <- store.list(gone)
            goneAttempts <- store.listAttempts(gone)
            keptCheckpoints <- store.list(kept)
            keptAttempts <- store.listAttempts(kept)
          yield assertTrue(
            goneCheckpoints.isEmpty,
            goneAttempts.isEmpty,
            keptCheckpoints.size == 1,
            keptAttempts.size == 1
          )
        }
      },
      test("attempt records round trip in insertion order, several for one key") {
        val runId = RunId("c-attempts")
        val failed = AttemptRecord(
          runId,
          NodeId("n"),
          Attempt.first,
          AttemptRecord.Outcome.Failed,
          Some(Money(50, "USD")),
          Some(TokenCount(100, 20)),
          t0
        )
        val succeeded = failed.copy(
          outcome = AttemptRecord.Outcome.Succeeded,
          cost = None,
          tokens = None,
          recordedAt = t0.plusSeconds(1)
        )
        withStore { store =>
          for
            _ <- store.recordAttempt(failed)
            _ <- store.recordAttempt(succeeded)
            listed <- store.listAttempts(runId)
          yield assertTrue(listed == Chunk(failed, succeeded))
        }
      }
    ),
    suite("through the interpreter")(
      test("a crash mid-Seq resumes at the failed node; the committed one is not re-run") {
        val runId = RunId("i-crash-seq")
        for
          leftCalls <- Ref.make(0)
          rightCalls <- Ref.make(0)
          shouldFail <- Ref.make(true)
          store <- ZIO.service[CheckpointStore]
          left = counting("left", leftCalls)
          right = Node.Effect[Any, String, Int, Int](
            NodeId("right"),
            i =>
              rightCalls.update(_ + 1) *>
                shouldFail.get.flatMap(f => if f then ZIO.fail("boom") else ZIO.succeed(i + 1)),
            intSchema
          )
          graph = Node.Seq(
            NodeId("pipeline"),
            left.asInstanceOf[Node[Any, String, Int, Int]],
            right
          )
          env = ZEnvironment(store)
          crashed <- Interpreter.run(graph, 10, runId).provideEnvironment(env).either
          _ <- shouldFail.set(false)
          resumed <- Interpreter.run(graph, 10, runId).provideEnvironment(env)
          leftRuns <- leftCalls.get
          rightRuns <- rightCalls.get
        yield assertTrue(crashed.isLeft, resumed == 21, leftRuns == 1, rightRuns == 2)
      },
      test("FanOut branches sharing a literal id do not collide") {
        val runId = RunId("i-fanout-collision")
        for
          callsA <- Ref.make(0)
          callsB <- Ref.make(0)
          store <- ZIO.service[CheckpointStore]
          fanOut = Node.FanOut[Any, Nothing, Int, Int](
            NodeId("fan"),
            NonEmptyChunk(counting("check", callsA), counting("check", callsB)),
            results => results.map(_.asInstanceOf[Int]).sum
          )
          env = ZEnvironment(store)
          first <- Interpreter.run(fanOut, 5, runId).provideEnvironment(env)
          second <- Interpreter.run(fanOut, 5, runId).provideEnvironment(env)
          aRuns <- callsA.get
          bRuns <- callsB.get
        yield assertTrue(first == 10 + 10, second == 20, aRuns == 1, bRuns == 1)
      },
      test("Verify's judge verdict is replayed, not re-invoked") {
        val runId = RunId("i-verify-replay")
        for
          innerCalls <- Ref.make(0)
          judgeCalls <- Ref.make(0)
          store <- ZIO.service[CheckpointStore]
          judge = new Node.Verify.Judge[Any, Nothing, Int]:
            def check(output: Int): UIO[Boolean] = judgeCalls.update(_ + 1).as(true)
          node = Node.Verify(NodeId("audit"), counting("double", innerCalls), judge)
          env = ZEnvironment(store)
          first <- Interpreter.run(node, 21, runId).provideEnvironment(env)
          second <- Interpreter.run(node, 21, runId).provideEnvironment(env)
          innerRuns <- innerCalls.get
          judgeRuns <- judgeCalls.get
        yield assertTrue(first == 42, second == 42, innerRuns == 1, judgeRuns == 1)
      },
      test("a node that fails after billing has its spend recorded as an attempt") {
        val runId = RunId("i-failed-spend")
        val node = Node.Effect[Any, String, Int, Int](
          NodeId("flaky"),
          _ =>
            Cost
              .report(Spend(Some(Money(50, "USD")), Some(TokenCount(100, 20))))
              .orDieWith(m => new AssertionError(m.toString)) *> ZIO.fail("malformed"),
          intSchema
        )
        for
          store <- ZIO.service[CheckpointStore]
          result <- Interpreter.run(node, 1, runId).provideEnvironment(ZEnvironment(store)).either
          checkpoint <- store.get(runId, NodeId("flaky"), Attempt.first)
          attempts <- store.listAttempts(runId)
        yield assertTrue(
          result == Left(GraphError.NodeFailed(NodeId("flaky"), "malformed")),
          checkpoint.isEmpty,
          attempts.size == 1,
          attempts.head.outcome == AttemptRecord.Outcome.Failed,
          attempts.head.cost.contains(Money(50, "USD")),
          attempts.head.tokens.contains(TokenCount(100, 20))
        )
      }
    )
  )
