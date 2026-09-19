package cairn

import zio.*

/**
 * Cost and token spend a node body reports while it runs.
 *
 * `+` accumulates rather than overwrites: a node body that makes two billed calls for one logical
 * execution (an internal rate-limit retry, say) must have both counted, not just the last one.
 */
final case class Spend(cost: Option[Money], tokens: Option[TokenCount]):
  def isEmpty: Boolean = cost.isEmpty && tokens.isEmpty

  def +(other: Spend): Either[CurrencyMismatch, Spend] =
    Spend
      .mergeCost(cost, other.cost)
      .map(mergedCost =>
        Spend(
          cost = mergedCost,
          tokens = Spend.merge(tokens, other.tokens)((a, b) =>
            TokenCount(a.input + b.input, a.output + b.output)
          )
        )
      )

object Spend:
  val empty: Spend = Spend(None, None)

  private def mergeCost(
      a: Option[Money],
      b: Option[Money]
  ): Either[CurrencyMismatch, Option[Money]] =
    (a, b) match
      case (Some(x), Some(y)) => (x + y).map(Some(_))
      case _ => Right(a.orElse(b))

  private def merge[A](a: Option[A], b: Option[A])(f: (A, A) => A): Option[A] =
    (a, b) match
      case (Some(x), Some(y)) => Some(f(x, y))
      case (Some(x), None) => Some(x)
      case (None, Some(y)) => Some(y)
      case (None, None) => None

/**
 * The node-body-local spend accumulator, and the only place a node body reports what a call cost.
 *
 * [[Interpreter.effect]] scopes [[ref]] with `FiberRef#locally` around every [[Node.Effect]] body:
 * reset to [[Spend.empty]] before the body runs, restored to whatever it held before on the way out
 * — success, failure or interruption alike. That restore is what keeps one node's spend from
 * leaking into a sibling's checkpoint; it is not something a node body has to remember to do
 * itself.
 *
 * Cost aggregation across a run is store summation (`store.list(runId)`, once the budget
 * interpreter lands), never live `FiberRef` propagation — see CLAUDE.md invariant #8. That only
 * holds because every `Effect` execution clears via `locally`, without exception. `FanOut` branches
 * each run this cycle entirely within their own forked fiber before the fork completes, so there is
 * nothing to combine across branches at the `FiberRef` level.
 */
object Cost:
  val ref: FiberRef[Spend] =
    Unsafe.unsafely(FiberRef.unsafe.make(Spend.empty))

  /**
   * Called by a node body (the `llm` module, today) after a billed call completes. Accumulates
   * rather than overwrites — see [[Spend.+]]. Fails with [[CurrencyMismatch]] if `spend` is priced
   * in a different currency than what this execution has already reported; the accumulator is left
   * unchanged in that case, so the mismatching spend is not recorded anywhere. The node body
   * decides what a mismatch means in its own error type.
   */
  def report(spend: Spend): IO[CurrencyMismatch, Unit] =
    ref.get.flatMap(current => ZIO.fromEither(current + spend)).flatMap(ref.set)
