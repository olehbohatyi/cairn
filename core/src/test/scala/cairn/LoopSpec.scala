package cairn

import zio.*
import zio.schema.Schema
import zio.test.*

/**
 * Structural behaviour of `Loop`, against `CheckpointStore.none` so that persistence is out of the
 * picture. Crash-resume / replay behaviour for `Loop` is tested in `cairn-store-memory`, which has
 * a store that actually remembers - matching how `InterpreterSpec` vs. `ReplaySpec` split the same
 * concern for `Effect`.
 *
 * No network calls - CLAUDE.md invariant #4.
 */
object LoopSpec extends ZIOSpecDefault:

  private given intSchema: Schema[Int] = Schema.primitive[Int]

  private val noStore = ZEnvironment(CheckpointStore.none)
  private val runId = RunId("loop-spec")

  /**
   * Body ignores feedback content and just doubles its input, but records every `feedback` it was
   * called with so tests can observe what the interpreter actually threaded through on retry.
   */
  private def body(
      calls: Ref[Int],
      feedbackSeen: Ref[List[Option[Node.Loop.Feedback]]]
  ): Node.Effect[Any, Nothing, (Int, Option[Node.Loop.Feedback]), Int] =
    Node.Effect(
      NodeId("propose"),
      { case (i, fb) => calls.update(_ + 1) *> feedbackSeen.update(_ :+ fb).as(i * 2) },
      intSchema
    )

  /**
   * Returns `acceptAt(callIndex)` where `callIndex` is 0 on the first call, 1 on the second, and so
   * on - so a test can say precisely which call index accepts.
   */
  private def acceptCounting(calls: Ref[Int], acceptAt: Int => Boolean): Int => UIO[Boolean] =
    _ => calls.getAndUpdate(_ + 1).map(acceptAt)

  def spec = suite("Loop")(
    test("an accepted first attempt succeeds without retrying") {
      for
        bodyCalls <- Ref.make(0)
        acceptCalls <- Ref.make(0)
        feedbackSeen <- Ref.make(List.empty[Option[Node.Loop.Feedback]])
        node = Node.Loop(
          NodeId("loop"),
          body(bodyCalls, feedbackSeen),
          acceptCounting(acceptCalls, _ => true),
          max = 3
        )
        result <- Interpreter.run(node, 5, runId).provideEnvironment(noStore)
        bRuns <- bodyCalls.get
        aRuns <- acceptCalls.get
        fb <- feedbackSeen.get
      yield assertTrue(result == 10, bRuns == 1, aRuns == 1, fb == List(None))
    },
    test("a rejected attempt retries with feedback, and the next attempt is accepted") {
      for
        bodyCalls <- Ref.make(0)
        acceptCalls <- Ref.make(0)
        feedbackSeen <- Ref.make(List.empty[Option[Node.Loop.Feedback]])
        node = Node.Loop(
          NodeId("loop"),
          body(bodyCalls, feedbackSeen),
          acceptCounting(acceptCalls, n => n == 1), // reject attempt 0, accept attempt 1
          max = 3
        )
        result <- Interpreter.run(node, 5, runId).provideEnvironment(noStore)
        bRuns <- bodyCalls.get
        fb <- feedbackSeen.get
      yield assertTrue(
        result == 10,
        bRuns == 2,
        fb.size == 2,
        fb.head.isEmpty, // first attempt: no prior feedback
        fb(1).isDefined // second attempt: fed back from the rejection
      )
    },
    test("exhausting every attempt fails with Exhausted, having run body exactly max times") {
      for
        bodyCalls <- Ref.make(0)
        acceptCalls <- Ref.make(0)
        feedbackSeen <- Ref.make(List.empty[Option[Node.Loop.Feedback]])
        node = Node.Loop(
          NodeId("loop"),
          body(bodyCalls, feedbackSeen),
          acceptCounting(acceptCalls, _ => false), // never accepts
          max = 3
        )
        result <- Interpreter.run(node, 5, runId).provideEnvironment(noStore).either
        bRuns <- bodyCalls.get
      yield assertTrue(result == Left(GraphError.Exhausted(NodeId("loop"), 3)), bRuns == 3)
    },
    test("max = 1 allows no retry: a single rejection exhausts immediately") {
      for
        bodyCalls <- Ref.make(0)
        acceptCalls <- Ref.make(0)
        feedbackSeen <- Ref.make(List.empty[Option[Node.Loop.Feedback]])
        node = Node.Loop(
          NodeId("loop"),
          body(bodyCalls, feedbackSeen),
          acceptCounting(acceptCalls, _ => false),
          max = 1
        )
        result <- Interpreter.run(node, 5, runId).provideEnvironment(noStore).either
        bRuns <- bodyCalls.get
      yield assertTrue(result == Left(GraphError.Exhausted(NodeId("loop"), 1)), bRuns == 1)
    },
    test("accept's own failure is attributed to the loop's id, not a derived one") {
      val failing = Node.Loop(
        NodeId("loop"),
        Node.Effect[Any, String, (Int, Option[Node.Loop.Feedback]), Int](
          NodeId("propose"),
          { case (i, _) => ZIO.succeed(i) },
          intSchema
        ),
        (_: Int) => ZIO.fail("accept infrastructure failure"),
        max = 3
      )
      for result <- Interpreter.run(failing, 5, runId).provideEnvironment(noStore).either
      yield assertTrue(
        result == Left(GraphError.NodeFailed(NodeId("loop"), "accept infrastructure failure"))
      )
    }
  )
