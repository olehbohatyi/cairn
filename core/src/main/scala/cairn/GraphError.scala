package cairn

/**
 * The closed failure channel for a graph run. No `Throwable` escapes here — see CLAUDE.md invariant
 * #3. A node's own domain error is carried in `E`.
 */
enum GraphError[+E]:
  case NodeFailed(nodeId: NodeId, error: E)
  case BudgetExceeded(spent: Money, ceiling: Money)
  case VerificationFailed(nodeId: NodeId, reason: String)
  case Exhausted(nodeId: NodeId, attempts: Int)

  /**
   * The checkpoint backend failed, or a committed value would not decode.
   *
   * Fifth case, added in Week 1 — CLAUDE.md's error model lists four. A store failure is genuinely
   * not a node failure: the node may never have run, or may have run and succeeded. Collapsing it
   * into `NodeFailed` would tell the caller something untrue. Update CLAUDE.md to match.
   */
  case StoreFailed(nodeId: NodeId, error: StoreError)

/**
 * `Suspended` is a *success* value, not an error — see CLAUDE.md, "Error model". A run that hits an
 * approval gate returns `Either[Suspended, O]`, never a `GraphError`.
 */
final case class Suspended(runId: RunId, pendingNodeId: NodeId, reason: String)

/**
 * Placeholder pending the budget interpreter (CLAUDE.md roadmap, Week 2). Deliberately minimal —
 * enough to compile `GraphError`, not a finished money type. Do not build currency conversion or
 * rounding logic on top of this until the budget interpreter actually needs it.
 */
final case class Money(cents: Long, currency: String):
  def +(other: Money): Money =
    require(currency == other.currency, s"currency mismatch: $currency vs ${other.currency}")
    Money(cents + other.cents, currency)

  def >(other: Money): Boolean =
    require(currency == other.currency, s"currency mismatch: $currency vs ${other.currency}")
    cents > other.cents

object Money:
  def eur(amount: Double): Money = Money(math.round(amount * 100), "EUR")
