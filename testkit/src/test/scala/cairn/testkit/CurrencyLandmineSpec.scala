package cairn.testkit

import cairn.*
import zio.*
import zio.schema.Schema
import zio.test.*

/**
 * `Spend` accumulation assumes single-currency pricing (CLAUDE.md, "Open decisions" — "`Spend`
 * accumulation assumes single-currency pricing"). `Spend.+` sums `Option[Money]` via `Money.+`,
 * which `require`s matching currencies and throws a bare `IllegalArgumentException` on mismatch.
 * Dormant today because every `Model` prices in `"USD"`; this test makes that a checked fact
 * instead of a comment nobody has run against.
 *
 * This test does NOT close the gap. It demonstrates the gap is real and behaves exactly as
 * documented - a currency conflict `die`s rather than failing typed, which is a real invariant #3
 * violation. The fix (a fixed ledger currency, or `Spend.+` returning a typed conflict instead of
 * delegating to `Money.+` unguarded) is deferred to the budget interpreter, same as CLAUDE.md
 * already records. If this test ever goes green on a `Right`/success outcome instead of a `die`,
 * that means someone fixed the landmine - update this test to assert the new, safe behaviour
 * instead of treating a passing "it dies" assertion as untouchable.
 *
 * No network calls - CLAUDE.md invariant #4.
 */
object CurrencyLandmineSpec extends ZIOSpecDefault:

  private given intSchema: Schema[Int] = Schema.primitive[Int]

  def spec = suite("currency landmine")(
    test("a node that reports spend in two different currencies dies rather than failing typed") {
      val node = Node.Effect[Any, Nothing, Int, Int](
        NodeId("mixed-currency"),
        i =>
          Cost.report(Spend(Some(Money(1, "USD")), None)) *>
            Cost.report(Spend(Some(Money(1, "EUR")), None)) *>
            ZIO.succeed(i),
        intSchema
      )
      for exit <- Interpreter
          .run(node, 1, RunId("currency-landmine"))
          .provideEnvironment(ZEnvironment(CheckpointStore.none))
          .exit
      yield assertTrue(
        exit.isFailure,
        exit.causeOption.exists(_.isDie),
        exit.causeOption
          .flatMap(_.dieOption)
          .exists(_.getMessage.contains("currency mismatch"))
      )
    }
  )
