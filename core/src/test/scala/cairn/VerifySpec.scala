package cairn

import zio.*
import zio.schema.Schema
import zio.test.*

/**
 * Verify is fail-closed (invariant #5): the only path to success is an explicit `true` from the
 * judge. These tests exist specifically to prove the dangerous adjacent-path failure mode does not
 * happen - a judge whose own effect fails, or dies, must never be silently treated as a pass.
 *
 * No network calls - CLAUDE.md invariant #4.
 */
object VerifySpec extends ZIOSpecDefault:

  private given intSchema: Schema[Int] = Schema.primitive[Int]

  private val noStore = ZEnvironment(CheckpointStore.none)
  private val runId = RunId("verify-spec")

  private def inner(value: Int): Node.Effect[Any, String, Int, Int] =
    Node.Effect(NodeId("inner"), _ => ZIO.succeed(value), intSchema)

  private def judgeReturning(verdict: Boolean): Node.Verify.Judge[Any, String, Int] =
    new Node.Verify.Judge[Any, String, Int]:
      def check(output: Int): IO[String, Boolean] = ZIO.succeed(verdict)

  private def judgeFailingWith(error: String): Node.Verify.Judge[Any, String, Int] =
    new Node.Verify.Judge[Any, String, Int]:
      def check(output: Int): IO[String, Boolean] = ZIO.fail(error)

  private def judgeDyingWith(t: Throwable): Node.Verify.Judge[Any, String, Int] =
    new Node.Verify.Judge[Any, String, Int]:
      def check(output: Int): IO[String, Boolean] = ZIO.die(t)

  def spec = suite("Verify")(
    test("an explicit true pass returns the inner node's output") {
      val node = Node.Verify(NodeId("verify"), inner(42), judgeReturning(true))
      for result <- Interpreter.run(node, 1, runId).provideEnvironment(noStore)
      yield assertTrue(result == 42)
    },
    test("an explicit false fails with VerificationFailed, not a silent pass") {
      val node = Node.Verify(NodeId("verify"), inner(42), judgeReturning(false))
      for result <- Interpreter.run(node, 1, runId).provideEnvironment(noStore).either
      yield assertTrue(
        result == Left(GraphError.VerificationFailed(NodeId("verify"), "judge rejected the output"))
      )
    },
    test(
      "a judge whose own effect fails (rate limit, timeout, malformed output) fails closed - never treated as a pass"
    ) {
      val node = Node.Verify(NodeId("verify"), inner(42), judgeFailingWith("rate limited"))
      for result <- Interpreter.run(node, 1, runId).provideEnvironment(noStore).either
      yield assertTrue(result == Left(GraphError.NodeFailed(NodeId("verify"), "rate limited")))
    },
    test(
      "a judge that dies (a genuine defect, not a typed failure) propagates as a defect - not caught, not swallowed into a pass"
    ) {
      val boom = new RuntimeException("boom")
      val node = Node.Verify(NodeId("verify"), inner(42), judgeDyingWith(boom))
      for exit <- Interpreter.run(node, 1, runId).provideEnvironment(noStore).exit
      yield assertTrue(exit.isFailure, exit.causeOption.exists(_.isDie))
    },
    test("the inner node's own failure short-circuits before the judge ever runs") {
      val failingInner =
        Node.Effect[Any, String, Int, Int](NodeId("inner"), _ => ZIO.fail("inner boom"), intSchema)
      for
        judgeCalled <- Ref.make(false)
        judge = new Node.Verify.Judge[Any, String, Int]:
          def check(output: Int): IO[String, Boolean] =
            judgeCalled.set(true).as(true)
        node = Node.Verify(NodeId("verify"), failingInner, judge)
        result <- Interpreter.run(node, 1, runId).provideEnvironment(noStore).either
        called <- judgeCalled.get
      yield assertTrue(
        result == Left(GraphError.NodeFailed(NodeId("inner"), "inner boom")),
        !called
      )
    }
  )
