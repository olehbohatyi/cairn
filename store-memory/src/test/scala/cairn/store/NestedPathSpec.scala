package cairn.store

import cairn.*
import zio.*
import zio.schema.Schema
import zio.test.*

/**
 * `Verify` nested inside a `FanOut` branch, exercised at depth rather than in isolation.
 *
 * `Week4Spec`'s "duplicate branch id" test proves `FanOut`'s positional qualification works for a
 * bare `Effect` branch. `ReplaySpec`'s judge-checkpoint test proves `Verify`'s `checkedJudge` works
 * at the *root* path. Neither proves that `Verify`'s own `path.descend` and `checkedJudge`'s
 * `path.qualify` correctly consume a *non-root* path handed down from an enclosing `FanOut` branch -
 * that composition has never actually run. Two branches, both wrapping a `Verify` around an
 * `Effect` that share the exact same literal `NodeId`s in both branches, so a wrong composition
 * would show up as one branch's checkpoint shadowing the other's - directly observable as a call
 * count of 0 (never ran, silently replayed someone else's output) or 2 (re-ran on the second
 * `Interpreter.run`, meaning it was never actually checkpointed under its own key).
 *
 * No network calls - CLAUDE.md invariant #4.
 */
object NestedPathSpec extends ZIOSpecDefault:

  private given intSchema: Schema[Int] = Schema.primitive[Int]

  private def verifiedBranch(
      innerCalls: Ref[Int],
      judgeCalls: Ref[Int]
  ): Node.Verify[Any, Nothing, Int, Int] =
    val effect = Node.Effect[Any, Nothing, Int, Int](
      NodeId("leaf"), // deliberately identical across both branches
      i => innerCalls.update(_ + 1).as(i + 1),
      intSchema
    )
    val judge = new Node.Verify.Judge[Any, Nothing, Int]:
      def check(output: Int): UIO[Boolean] = judgeCalls.update(_ + 1).as(true)
    Node.Verify(NodeId("v"), effect, judge) // deliberately identical across both branches

  def spec = suite("nested path qualification")(
    test(
      "two FanOut branches each wrapping a Verify around an Effect, all sharing identical literal ids: both replay independently on a second run"
    ) {
      val runId = RunId("nested-fanout-verify")
      for
        innerA <- Ref.make(0)
        innerB <- Ref.make(0)
        judgeA <- Ref.make(0)
        judgeB <- Ref.make(0)
        store <- InMemoryStore.make

        fanOut = Node.FanOut[Any, Nothing, Int, Int](
          NodeId("fan"),
          NonEmptyChunk(verifiedBranch(innerA, judgeA), verifiedBranch(innerB, judgeB)),
          results => results.map(_.asInstanceOf[Int]).sum
        )

        first <- Interpreter.run(fanOut, 10, runId).provideEnvironment(ZEnvironment(store))
        second <- Interpreter.run(fanOut, 10, runId).provideEnvironment(ZEnvironment(store))

        aInner <- innerA.get
        bInner <- innerB.get
        aJudge <- judgeA.get
        bJudge <- judgeB.get
      yield assertTrue(
        first == 22, // (10+1) + (10+1)
        second == 22,
        aInner == 1,
        bInner == 1,
        aJudge == 1,
        bJudge == 1
      )
    }
  )
