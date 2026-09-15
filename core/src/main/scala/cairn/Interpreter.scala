package cairn

import zio.*

/**
 * The bare interpreter: executes a graph with no checkpointing, no budget, no tiering. This is
 * intentionally not what ships — `store-memory` wraps this with checkpoint commits (roadmap Week 1
 * continues there), and `withBudget` / `withCheckpoints` in the README are separate interpreter
 * layers over this one (see CLAUDE.md, "What wraps the graph at run time").
 *
 * Exists so `Effect` / `Seq` / `FanOut` are exercisable and testable before any persistence module
 * lands. `Loop`, `Verify` and `Gate` are not wired yet — running one is a defect, not a silent
 * no-op, so mistakes surface immediately rather than producing a graph that quietly skips a
 * verifier.
 */
object Interpreter:

  def run[R, E, I, O](node: Node[R, E, I, O], input: I): ZIO[R, GraphError[E], O] =
    node match
      case n: Node.Effect[R, E, I, O] @unchecked =>
        n.run(input).mapError(GraphError.NodeFailed(n.id, _))

      case n: Node.Seq[R, E, I, m, O] @unchecked =>
        run(n.left, input).flatMap(mid => run(n.right, mid))

      case n: Node.FanOut[R, E, I, O] @unchecked =>
        ZIO
          .foreachPar(n.branches)(branch => run(branch, input))
          .map(results => n.join(results.map(identity)))

      case n: Node.Loop[?, ?, ?, ?] =>
        ZIO.die(
          new NotImplementedError(
            s"Node.Loop(${n.id.value}) has no interpreter yet — see CLAUDE.md roadmap Week 3"
          )
        )

      case n: Node.Verify[?, ?, ?, ?] =>
        ZIO.die(
          new NotImplementedError(
            s"Node.Verify(${n.id.value}) has no interpreter yet — see CLAUDE.md roadmap Week 3." +
              " This must never silently pass; failing loudly here is deliberate."
          )
        )

      case n: Node.Gate[?, ?, ?, ?] =>
        ZIO.die(
          new NotImplementedError(
            s"Node.Gate(${n.id.value}) has no interpreter yet — see CLAUDE.md roadmap Week 5"
          )
        )
