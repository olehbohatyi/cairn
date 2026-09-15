package cairn

import zio.*

/**
 * A graph is data, not a function — see CLAUDE.md, "The one decision everything follows from". Six
 * cases, deliberately closed: adding a seventh means touching every interpreter, so a new
 * cross-cutting concern (retry, timeout, cache) should be an interpreter over [[Node.Effect]]
 * rather than a new constructor. Get agreement before adding a case.
 */
sealed trait Node[-R, +E, -I, +O]:
  def id: NodeId

object Node:

  /**
   * A single unit of work — may be an LLM call, plain ZIO code, anything. The graph does not
   * distinguish; that classification lives in `llm`, not `core` (see CLAUDE.md invariant #1).
   */
  final case class Effect[R, E, I, O](id: NodeId, run: I => ZIO[R, E, O]) extends Node[R, E, I, O]

  /**
   * Sequential composition. The interpreter checkpoints after `left` commits and before `right`
   * begins, so a crash between the two resumes at `right` without re-running `left`.
   */
  final case class Seq[R, E, I, M, O](
      id: NodeId,
      left: Node[R, E, I, M],
      right: Node[R, E, M, O]
  ) extends Node[R, E, I, O]

  /**
   * Parallel branches, joined into one output. Each branch is checkpointed independently, which is
   * what lets a crash mid-fan-out skip only the branches that already committed on restart.
   *
   * The existential branch type and `Chunk[Any]` join input are the accepted escape hatch noted in
   * CLAUDE.md — they are an interpreter implementation detail and must never surface in a public
   * signature.
   */
  final case class FanOut[R, E, I, O](
      id: NodeId,
      branches: NonEmptyChunk[Node[R, E, I, ?]],
      join: Chunk[Any] => O
  ) extends Node[R, E, I, O]

  /**
   * Propose, validate, retry up to `max` attempts, feeding the failure back into the next attempt.
   * `onExhausted` is not modelled here yet — the exhaustion path is `GraphError.Exhausted`; a
   * richer recovery hook is still open, see CLAUDE.md roadmap Week 3.
   *
   * TODO(week 3): `Feedback` is a placeholder. It should carry whatever the `validate` step wants
   * the next `propose` attempt to see (e.g. the validation failure reason) — not yet designed.
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
   *
   * TODO(week 3): `Judge` is a placeholder pending the verification design (fresh-context
   * re-invocation vs. a plain code check — see the `Verdict.defaultFail` example in README.md).
   */
  final case class Verify[R, E, I, O](
      id: NodeId,
      inner: Node[R, E, I, O],
      judge: Verify.Judge[R, E, O]
  ) extends Node[R, E, I, O]

  object Verify:
    trait Judge[-R, +E, -O]:
      def check(output: O): ZIO[R, E, Boolean]

  /**
   * Suspends the run when `when` holds, rather than failing it. See [[Suspended]] and CLAUDE.md,
   * "Suspension mechanics" — resume reuses the crash-recovery path, so this stays deliberately thin
   * until that lands (roadmap Week 5).
   */
  final case class Gate[R, E, I, O](
      id: NodeId,
      inner: Node[R, E, I, O],
      when: O => Boolean
  ) extends Node[R, E, I, O]
