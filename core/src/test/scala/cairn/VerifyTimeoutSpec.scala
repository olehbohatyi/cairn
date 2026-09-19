package cairn

import zio.*
import zio.schema.Schema
import zio.test.*

/**
 * `Verify.timeout` uses `TestClock`, virtual time - never a real sleep, so this stays fast and
 * deterministic. The standard zio-test idiom: fork the effect under test, advance the virtual
 * clock, then join. No network calls - CLAUDE.md invariant #4.
 */
object VerifyTimeoutSpec extends ZIOSpecDefault:

  private given intSchema: Schema[Int] = Schema.primitive[Int]
  private val noStore = ZEnvironment(CheckpointStore.none)
  private val runId = RunId("verify-timeout")

  private val inner =
    Node.Effect[Any, Nothing, Int, Int](NodeId("inner"), ZIO.succeed(_), intSchema)

  def spec = suite("Verify timeout")(
    test("a judge that never completes fails with JudgeTimedOut once the timeout elapses") {
      val neverJudge = new Node.Verify.Judge[Any, Nothing, Int]:
        def check(output: Int): UIO[Boolean] = ZIO.never
      val node = Node.Verify(NodeId("verify"), inner, neverJudge, timeout = Some(5.seconds))
      for
        fiber <- Interpreter.run(node, 1, runId).provideEnvironment(noStore).either.fork
        _ <- TestClock.adjust(5.seconds)
        result <- fiber.join
      yield assertTrue(result == Left(GraphError.JudgeTimedOut(NodeId("verify"), 5.seconds)))
    },
    test("a judge that answers before the timeout succeeds normally - timeout doesn't fire early") {
      val fastJudge = new Node.Verify.Judge[Any, Nothing, Int]:
        def check(output: Int): UIO[Boolean] = ZIO.succeed(true)
      val node = Node.Verify(NodeId("verify"), inner, fastJudge, timeout = Some(5.seconds))
      for result <- Interpreter.run(node, 7, runId).provideEnvironment(noStore)
      yield assertTrue(result == 7)
    },
    test("no timeout set (None) means an unbounded wait - unchanged prior behavior") {
      val fastJudge = new Node.Verify.Judge[Any, Nothing, Int]:
        def check(output: Int): UIO[Boolean] = ZIO.succeed(true)
      val node = Node.Verify(NodeId("verify"), inner, fastJudge) // timeout defaults to None
      for result <- Interpreter.run(node, 3, runId).provideEnvironment(noStore)
      yield assertTrue(result == 3)
    }
  )
