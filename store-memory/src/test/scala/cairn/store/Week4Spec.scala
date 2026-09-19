package cairn.store

import cairn.*
import zio.*
import zio.schema.Schema
import zio.test.*

/**
 * The three genuinely new-this-week behaviors from the Week 4 open-decision triage: node id
 * collisions (`FanOut` branches), failed-node spend now recorded as an `AttemptRecord`, and the
 * blob-size ceiling. `Verify`'s judge-checkpoint fix is tested in `ReplaySpec` instead - it is a
 * replay behavior, and that file is already where the other replay tests live, so its test was
 * updated in place there rather than duplicated here. `Verify` timeout lives in `core`'s
 * `VerifyTimeoutSpec`, since it needs `TestClock` and no store-replay behavior.
 *
 * No network calls - CLAUDE.md invariant #4.
 */
object Week4Spec extends ZIOSpecDefault:

  private given intSchema: Schema[Int] = Schema.primitive[Int]

  private def counting(id: String, calls: Ref[Int]): Node.Effect[Any, Nothing, Int, Int] =
    Node.Effect(NodeId(id), i => calls.update(_ + 1).as(i * 2), intSchema)

  def spec = suite("week 4")(
    test(
      "two FanOut branches sharing a literal id do not collide - each gets its own checkpoint"
    ) {
      val runId = RunId("fanout-collision")
      for
        callsA <- Ref.make(0)
        callsB <- Ref.make(0)
        store <- InMemoryStore.make
        // Both branches are literally the same NodeId - the named collision
        // case from CLAUDE.md's Open decisions.
        branchA = counting("check", callsA)
        branchB = counting("check", callsB)
        fanOut = Node.FanOut[Any, Nothing, Int, Int](
          NodeId("fan"),
          NonEmptyChunk(branchA, branchB),
          results => results.map(_.asInstanceOf[Int]).sum
        )
        first <- Interpreter.run(fanOut, 5, runId).provideEnvironment(ZEnvironment(store))
        second <- Interpreter.run(fanOut, 5, runId).provideEnvironment(ZEnvironment(store))
        aRuns <- callsA.get
        bRuns <- callsB.get
      yield assertTrue(
        first == 20, // (5*2) + (5*2)
        second == 20,
        // Before path qualification, both branches shared the bare id
        // "check" as their store key: whichever committed first would have
        // made the second replay its output instead of running - one of
        // these counters would have stayed 0. Path qualification means both
        // ran once on the first pass and neither reruns on the second.
        aRuns == 1,
        bRuns == 1
      )
    },
    test(
      "a node that fails after billing real tokens has its spend recorded as an attempt, with no checkpoint"
    ) {
      val runId = RunId("failed-spend")
      val failingAfterSpend = Node.Effect[Any, String, Int, Int](
        NodeId("flaky"),
        _ =>
          Cost.report(Spend(Some(Money(50, "USD")), Some(TokenCount(100, 20)))) *>
            ZIO.fail("malformed reply"),
        intSchema
      )
      for
        store <- InMemoryStore.make
        result <- Interpreter
          .run(failingAfterSpend, 1, runId)
          .provideEnvironment(ZEnvironment(store))
          .either
        checkpoint <- store.get(runId, NodeId("flaky"), Attempt.first)
        attempts <- store.listAttempts(runId)
      yield assertTrue(
        result == Left(GraphError.NodeFailed(NodeId("flaky"), "malformed reply")),
        checkpoint.isEmpty, // no checkpoint - the node failed
        attempts.size == 1,
        attempts.head.outcome == AttemptRecord.Outcome.Failed,
        attempts.head.cost.contains(Money(50, "USD")), // but the spend was NOT silently dropped
        attempts.head.tokens.contains(TokenCount(100, 20))
      )
    },
    test(
      "a checkpoint value over the size ceiling fails the node and records the spend as an attempt, not silently"
    ) {
      val runId = RunId("too-large")
      val huge = "x" * (CheckpointSize.MaxApproxBytes.toInt + 1)
      given Schema[String] = Schema.primitive[String]
      val node = Node.Effect[Any, Nothing, Int, String](
        NodeId("extract"),
        _ => Cost.report(Spend(Some(Money(10, "USD")), None)) *> ZIO.succeed(huge),
        summon[Schema[String]]
      )
      for
        store <- InMemoryStore.make
        result <- Interpreter.run(node, 1, runId).provideEnvironment(ZEnvironment(store)).either
        checkpoint <- store.get(runId, NodeId("extract"), Attempt.first)
        attempts <- store.listAttempts(runId)
      yield assertTrue(
        result.isLeft,
        // NodeId is an opaque type with no `unapply`, so it can't be matched
        // as a literal pattern like a case class - compare by equality
        // inside the match instead of `case NodeId("extract") =>`.
        result match
          case Left(
                GraphError
                  .StoreFailed(nodeId, StoreError.ValueTooLarge(rid, qualifiedId, bytes, ceiling))
              ) =>
            nodeId == NodeId("extract") &&
            rid == runId &&
            qualifiedId == NodeId("extract") && // root path: qualified id equals the bare id
            bytes > ceiling
          case _ => false
        ,
        checkpoint.isEmpty,
        attempts.size == 1,
        attempts.head.outcome == AttemptRecord.Outcome.Failed,
        attempts.head.cost.contains(Money(10, "USD"))
      )
    }
  )
