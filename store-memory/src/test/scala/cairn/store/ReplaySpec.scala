package cairn.store

import cairn.*
import zio.*
import zio.schema.Schema
import zio.test.*

/**
 * The behaviour the whole library exists for: a node that already committed does not run again.
 * Every test here is pure Scala — no network calls, per CLAUDE.md invariant #4.
 */
object ReplaySpec extends ZIOSpecDefault:

  private given Schema[Int] = Schema.primitive[Int]

  /**
   * An Effect that counts its own executions, so "did the body run?" is an observable fact rather
   * than an inference from timing.
   */
  private def counting(id: String, calls: Ref[Int]): Node.Effect[Any, Nothing, Int, Int] =
    Node.Effect(
      NodeId(id),
      i => calls.update(_ + 1).as(i * 2),
      summon[Schema[Int]]
    )

  def spec = suite("replay")(
    test("a committed node is not executed again on a second run with the same runId") {
      val runId = RunId("run-1")
      for
        calls <- Ref.make(0)
        store <- InMemoryStore.make
        node = counting("double", calls)
        first <- Interpreter.run(node, 21, runId).provideEnvironment(ZEnvironment(store))
        second <- Interpreter.run(node, 21, runId).provideEnvironment(ZEnvironment(store))
        count <- calls.get
      yield assertTrue(
        first == 42,
        second == 42, // same answer
        count == 1 // but the body ran once
      )
    },
    test("a different runId re-executes the node") {
      for
        calls <- Ref.make(0)
        store <- InMemoryStore.make
        node = counting("double", calls)
        _ <- Interpreter.run(node, 1, RunId("a")).provideEnvironment(ZEnvironment(store))
        _ <- Interpreter.run(node, 1, RunId("b")).provideEnvironment(ZEnvironment(store))
        count <- calls.get
      yield assertTrue(count == 2)
    },
    test("a crash mid-Seq resumes at the failed node, not from the start") {
      val runId = RunId("crash-1")
      for
        firstCalls <- Ref.make(0)
        secondCalls <- Ref.make(0)
        store <- InMemoryStore.make
        shouldFail <- Ref.make(true)

        left = Node.Effect[Any, String, Int, Int](
          NodeId("left"),
          i => firstCalls.update(_ + 1).as(i * 2),
          summon[Schema[Int]]
        )
        right = Node.Effect[Any, String, Int, Int](
          NodeId("right"),
          i =>
            secondCalls.update(_ + 1) *>
              ShouldFail(shouldFail).flatMap {
                case true => ZIO.fail("boom")
                case false => ZIO.succeed(i + 1)
              },
          summon[Schema[Int]]
        )
        graph = Node.Seq(NodeId("pipeline"), left, right)

        crashed <- Interpreter
          .run(graph, 10, runId)
          .provideEnvironment(ZEnvironment(store))
          .either
        _ <- shouldFail.set(false)
        resumed <- Interpreter
          .run(graph, 10, runId)
          .provideEnvironment(ZEnvironment(store))
        leftRuns <- firstCalls.get
        rightRuns <- secondCalls.get
      yield assertTrue(
        crashed.isLeft,
        resumed == 21, // (10 * 2) + 1
        leftRuns == 1, // replayed from the checkpoint, not re-executed
        rightRuns == 2 // genuinely retried
      )
    },
    test("committing the same key twice is rejected rather than overwriting") {
      val checkpoint = Checkpoint(
        runId = RunId("dup"),
        nodeId = NodeId("n"),
        attempt = Attempt.first,
        value = summon[Schema[Int]].toDynamic(1),
        cost = None,
        tokens = None,
        committedAt = java.time.Instant.EPOCH
      )
      for
        store <- InMemoryStore.make
        _ <- store.commit(checkpoint)
        second <- store.commit(checkpoint.copy(value = summon[Schema[Int]].toDynamic(999))).either
        stored <- store.get(RunId("dup"), NodeId("n"), Attempt.first)
      yield assertTrue(
        second == Left(StoreError.AlreadyCommitted(RunId("dup"), NodeId("n"), Attempt.first)),
        stored.map(_.value).contains(summon[Schema[Int]].toDynamic(1))
      )
    }
  )

  private def ShouldFail(ref: Ref[Boolean]): UIO[Boolean] = ref.get
