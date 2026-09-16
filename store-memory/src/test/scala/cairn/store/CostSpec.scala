package cairn.store

import cairn.*
import zio.*
import zio.schema.Schema
import zio.test.*

/**
 * [[Cost]] is the one place a fiber-local side channel is trusted instead of a typed value, so its
 * two failure modes get their own tests rather than relying on [[ReplaySpec]] to catch them by
 * accident: under-accumulation (a node body bills twice, only the last report counts) and leakage
 * (a failed node's spend bleeds into whatever runs next on the same fiber). No network calls —
 * CLAUDE.md invariant #4.
 */
object CostSpec extends ZIOSpecDefault:

  private given Schema[Int] = Schema.primitive[Int]

  private def billing(id: String, spend: Spend*): Node.Effect[Any, String, Int, Int] =
    Node.Effect(
      NodeId(id),
      i => ZIO.foreachDiscard(spend)(Cost.report).as(i),
      summon[Schema[Int]]
    )

  private def failingAfterBilling(id: String, spend: Spend): Node.Effect[Any, String, Int, Int] =
    Node.Effect(
      NodeId(id),
      _ => Cost.report(spend) *> ZIO.fail("boom"),
      summon[Schema[Int]]
    )

  def spec = suite("cost")(
    test("a node's reported spend is attached to its checkpoint") {
      val spend = Spend(Some(Money(5, "USD")), Some(TokenCount(10, 20)))
      val node = billing("single-report", spend)
      val runId = RunId("single")
      for
        store <- InMemoryStore.make
        _ <- Interpreter.run(node, 1, runId).provideEnvironment(ZEnvironment(store))
        committed <- store.get(runId, NodeId("single-report"), Attempt.first)
      yield assertTrue(
        committed.map(_.cost) == Some(spend.cost),
        committed.map(_.tokens) == Some(spend.tokens)
      )
    },
    test("multiple reports within one execution accumulate rather than overwrite") {
      val first = Spend(Some(Money(1, "USD")), Some(TokenCount(1, 1)))
      val second = Spend(Some(Money(2, "USD")), Some(TokenCount(2, 2)))
      val node = billing("double-report", first, second)
      val runId = RunId("double")
      for
        store <- InMemoryStore.make
        _ <- Interpreter.run(node, 1, runId).provideEnvironment(ZEnvironment(store))
        committed <- store.get(runId, NodeId("double-report"), Attempt.first)
      yield assertTrue(
        committed.flatMap(_.cost) == Some(Money(3, "USD")),
        committed.flatMap(_.tokens) == Some(TokenCount(3, 3))
      )
    },
    test("spend from a failed node does not leak into the next node's checkpoint") {
      val expensive = failingAfterBilling(
        "billed-then-fails",
        Spend(Some(Money(99, "USD")), Some(TokenCount(50, 50)))
      )
      val quiet = Node.Effect[Any, String, Int, Int](
        NodeId("quiet"),
        i => ZIO.succeed(i),
        summon[Schema[Int]]
      )
      val runId = RunId("leak")
      for
        store <- InMemoryStore.make
        env = ZEnvironment(store)
        _ <- Interpreter.run(expensive, 1, runId).provideEnvironment(env).either
        _ <- Interpreter.run(quiet, 1, runId).provideEnvironment(env)
        committed <- store.get(runId, NodeId("quiet"), Attempt.first)
      yield assertTrue(committed.exists(c => c.cost.isEmpty && c.tokens.isEmpty))
    }
  )
