package cairn

import zio.*
import zio.schema.Schema
import zio.test.*

/**
 * Combining amounts in different currencies is a typed failure, not a defect (CLAUDE.md invariant
 * #3). This replaces `CurrencyLandmineSpec`, which asserted the old behaviour - a `die` carrying a
 * bare `IllegalArgumentException` - and said to update it once the gap was fixed.
 *
 * No network calls - CLAUDE.md invariant #4.
 */
object CurrencyMismatchSpec extends ZIOSpecDefault:

  private given intSchema: Schema[Int] = Schema.primitive[Int]

  def spec = suite("currency mismatch")(
    test("Money.+ adds amounts in the same currency") {
      assertTrue(Money(1, "USD") + Money(2, "USD") == Right(Money(3, "USD")))
    },
    test("Money.+ on different currencies is a typed Left, not an exception") {
      assertTrue(Money(1, "USD") + Money(1, "EUR") == Left(CurrencyMismatch("USD", "EUR")))
    },
    test("Spend.+ carries the mismatch through instead of throwing") {
      val usd = Spend(Some(Money(1, "USD")), Some(TokenCount(1, 1)))
      val eur = Spend(Some(Money(1, "EUR")), Some(TokenCount(2, 2)))
      assertTrue(
        usd + eur == Left(CurrencyMismatch("USD", "EUR")),
        usd + usd == Right(Spend(Some(Money(2, "USD")), Some(TokenCount(2, 2))))
      )
    },
    test("a mismatching report fails typed and leaves the accumulator unchanged") {
      for outcome <- Cost.ref.locally(Spend.empty)(
          for
            _ <- Cost.report(Spend(Some(Money(1, "USD")), None))
            second <- Cost.report(Spend(Some(Money(1, "EUR")), None)).either
            after <- Cost.ref.get
          yield (second, after)
        )
      yield assertTrue(
        outcome._1 == Left(CurrencyMismatch("USD", "EUR")),
        // The EUR spend is not recorded anywhere - see Cost.report's doc comment.
        outcome._2 == Spend(Some(Money(1, "USD")), None)
      )
    },
    test("a node body that surfaces the mismatch fails the run as NodeFailed, not a defect") {
      val node = Node.Effect[Any, CurrencyMismatch, Int, Int](
        NodeId("mixed-currency"),
        i =>
          Cost.report(Spend(Some(Money(1, "USD")), None)) *>
            Cost.report(Spend(Some(Money(1, "EUR")), None)) *>
            ZIO.succeed(i),
        intSchema
      )
      for exit <- Interpreter
          .run(node, 1, RunId("currency-mismatch"))
          .provideEnvironment(ZEnvironment(CheckpointStore.none))
          .exit
      yield assertTrue(
        exit == Exit.fail(
          GraphError.NodeFailed(NodeId("mixed-currency"), CurrencyMismatch("USD", "EUR"))
        )
      )
    }
  )
