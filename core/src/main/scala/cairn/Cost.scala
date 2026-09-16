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

  def +(other: Spend): Spend =
    Spend(
      cost = Spend.merge(cost, other.cost)(_ + _),
      tokens = Spend.merge(tokens, other.tokens)((a, b) =>
        TokenCount(a.input + b.input, a.output + b.output)
      )
    )

object Spend:
  val empty: Spend = Spend(None, None)

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
   * rather than overwrites — see [[Spend.+]].
   */
  def report(spend: Spend): UIO[Unit] = ref.update(_ + spend)
