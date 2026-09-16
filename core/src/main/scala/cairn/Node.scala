package cairn

import zio.*
import zio.schema.Schema

/**
 * A graph is data, not a function — see CLAUDE.md, "The one decision everything follows from". Six
 * cases, deliberately closed: adding a seventh means touching every interpreter, so a new
 * cross-cutting concern (retry, timeout, cache) should be an interpreter over [[Node.Effect]]
 * rather than a new constructor. Get agreement before adding a case.
 *
 * Invariant in all four parameters, deliberately. `O` appears in result position ([[Node.Effect]])
 * and in argument position ([[Node.Loop.accept]], [[Node.Gate.when]]), and `R` likewise, so no
 * consistent variance annotation exists. Invariance costs some convenience at composition sites and
 * buys a type that actually compiles.
 */
sealed trait Node[R, E, I, O]:
  def id: NodeId

object Node:

  /**
   * A single unit of work — may be an LLM call, plain ZIO code, anything. The graph does not
   * distinguish; that classification lives in `llm`, not `core` (see CLAUDE.md invariant #1).
   *
   * `outputSchema` is what makes the node checkpointable: the interpreter encodes the result to a
   * `DynamicValue` through it. Effect is the only case that carries one, because it is the only
   * case that produces a genuinely new value — [[Seq]] returns its right child's output and
   * [[FanOut]] joins its branches', and both of those are already checkpointed at the leaves.
   * Checkpoints are taken at Effect boundaries only.
   */
  final case class Effect[R, E, I, O](
      id: NodeId,
      run: I => ZIO[R, E, O],
      outputSchema: Schema[O]
  ) extends Node[R, E, I, O]

  /**
   * Sequential composition. `left` commits its checkpoint before `right` begins, so a crash between
   * the two resumes at `right` without re-running `left`.
   */
  final case class Seq[R, E, I, M, O](
      id: NodeId,
      left: Node[R, E, I, M],
      right: Node[R, E, M, O]
  ) extends Node[R, E, I, O]

  /**
   * Parallel branches, joined into one output. Each branch checkpoints independently, which is what
   * lets a crash mid-fan-out skip only the branches that already committed.
   *
   * The existential branch type and `Chunk[Any]` join input are the accepted escape hatch noted in
   * CLAUDE.md — an interpreter implementation detail that must never surface in a public signature.
   */
  final case class FanOut[R, E, I, O](
      id: NodeId,
      branches: NonEmptyChunk[Node[R, E, I, ?]],
      join: Chunk[Any] => O
  ) extends Node[R, E, I, O]

  /**
   * Propose, validate, retry up to `max` attempts, feeding the failure back into the next attempt.
   * Exhaustion surfaces as `GraphError.Exhausted`. `attempt` is the same counter threaded through
   * every other node - `Loop` is simply the only case that advances it, incrementing by one per
   * rejection and leaving it untouched on every other path. See `Interpreter.iterate`.
   *
   * `accept`'s verdict is checkpointed under a derived id (`"<loopId>/accept"`), same `attempt`,
   * honoring the commitment in `Ids.scala`'s `Attempt` doc comment - a resumed loop does not re-run
   * an expensive validation any more than it re-runs `body`.
   *
   * OPEN: `accept: O => ZIO[R, E, Boolean]` has no channel to explain *why* it rejected - only
   * whether. `Interpreter.iterate` therefore synthesizes a generic
   * `Feedback(s"attempt N rejected")` on retry, which carries no information about what was wrong
   * with attempt N. Real validation-failure content (what the README's repair-loop scenario
   * actually needs) requires widening `accept`'s signature - e.g. to
   * `O => ZIO[R, E, Either[Feedback, Unit]]` - which is a change to an existing case, not done
   * without asking. Until that's decided, `Feedback` is present in the type but empty in practice.
   */
  final case class Loop[R, E, I, O](
      id: NodeId,
      body: Node[R, E, (I, Option[Loop.Feedback]), O],
      accept: O => ZIO[R, E, Boolean],
      max: Int
  ) extends Node[R, E, I, O]

  object Loop:
    opaque type Feedback = String
    object Feedback:
      def apply(reason: String): Feedback = reason
      extension (f: Feedback) def reason: String = f

  /**
   * Wraps `inner`; `judge` must return an explicit pass for the run to continue. Fail-closed per
   * CLAUDE.md invariant #5 — there is no path to success that skips this check.
   * `Interpreter.verify` enforces this with a plain `if verdict then succeed else fail`, not a fold
   * that could quietly treat a judge failure as a pass; see that method's doc comment for exactly
   * what does and does not fail closed (a dying judge, a judge with no timeout).
   *
   * OPEN: the judge's verdict is not checkpointed anywhere - only `inner`'s output is, and only if
   * `inner` is an `Effect`. A crash after this node has already passed replays `inner` for free on
   * resume but re-invokes `judge` from scratch every time, unconditionally. That is a real cost,
   * not just a correctness footnote, for exactly the fresh-context second-model-call judge this
   * library is built around. See CLAUDE.md, "Open decisions".
   */
  final case class Verify[R, E, I, O](
      id: NodeId,
      inner: Node[R, E, I, O],
      judge: Verify.Judge[R, E, O]
  ) extends Node[R, E, I, O]

  object Verify:
    trait Judge[R, E, O]:
      def check(output: O): ZIO[R, E, Boolean]

  /**
   * Suspends the run when `when` holds, rather than failing it. See [[Suspended]] and CLAUDE.md,
   * "Suspension mechanics" — resume reuses the crash-recovery path, so this stays thin until that
   * lands (Week 5).
   */
  final case class Gate[R, E, I, O](
      id: NodeId,
      inner: Node[R, E, I, O],
      when: O => Boolean
  ) extends Node[R, E, I, O]
