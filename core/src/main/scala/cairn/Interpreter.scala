package cairn

import zio.*

/**
 * Executes a graph, committing a checkpoint after every [[Node.Effect]] and replaying any that
 * already committed for this `runId`.
 *
 * This is the only interpreter. Budget, tiering and tracing arrive as layers *over* this one
 * (CLAUDE.md, "What wraps the graph at run time"); running without persistence is
 * `CheckpointStore.none`, not a second implementation.
 *
 * `Loop`, `Verify` and `Gate` are not wired yet. Reaching one is a defect, not a silent no-op - a
 * `Verify` node that quietly passed would break invariant #5 in the worst possible way, so it dies
 * loudly instead.
 */
object Interpreter:

  /**
   * The caller supplies `runId`; cairn never generates one. See [[RunId]] for why that is
   * load-bearing rather than an inconvenience.
   *
   * `attempt` starts at [[Attempt.first]] and is advanced only by [[Node.Loop]], never by crash
   * recovery.
   */
  def run[R, E, I, O](
      node: Node[R, E, I, O],
      input: I,
      runId: RunId
  ): ZIO[R & CheckpointStore, GraphError[E], O] =
    run(node, input, runId, Attempt.first)

  private def run[R, E, I, O](
      node: Node[R, E, I, O],
      input: I,
      runId: RunId,
      attempt: Attempt
  ): ZIO[R & CheckpointStore, GraphError[E], O] =
    node match
      case n: Node.Effect[R, E, I, O] =>
        effect(n, input, runId, attempt)

      case n: Node.Seq[R, E, I, ?, O] =>
        runSeq(n, input, runId, attempt)

      case n: Node.FanOut[R, E, I, O] =>
        fanOut(n, input, runId, attempt)

      case n: Node.Loop[R, E, I, O] =>
        notYetInterpreted(n.id, "Week 3")

      case n: Node.Verify[R, E, I, O] =>
        notYetInterpreted(
          n.id,
          "Week 3 - must never silently pass; failing loudly here is deliberate"
        )

      case n: Node.Gate[R, E, I, O] =>
        notYetInterpreted(n.id, "Week 5")

  /**
   * OPEN (question 5): `foreachPar` is fail-fast. When one branch fails, ZIO interrupts its
   * siblings mid-flight - which throws away an LLM call that has already been paid for, and can
   * leave a fan-out partially checkpointed (some branches committed, some interrupted before
   * committing). That partial state is *safe* on resume, because committed branches replay and
   * interrupted ones re-execute, but it is not free.
   *
   * The alternative is to let every branch run to completion and collect failures, trading
   * money-on-a-doomed-run for money-on-a-cancelled-call. Undecided. This line is the entire
   * decision - changing it is one call site, so deferring costs nothing.
   */
  private def fanOut[R, E, I, O](
      n: Node.FanOut[R, E, I, O],
      input: I,
      runId: RunId,
      attempt: Attempt
  ): ZIO[R & CheckpointStore, GraphError[E], O] =
    ZIO
      .foreachPar(n.branches)(branch => run(branch, input, runId, attempt))
      .map(results => n.join(Chunk.fromIterable(results)))

  /**
   * Split out so the existential middle type `M` is bound by the method's own type parameter rather
   * than inferred at the match site.
   */
  private def runSeq[R, E, I, M, O](
      n: Node.Seq[R, E, I, M, O],
      input: I,
      runId: RunId,
      attempt: Attempt
  ): ZIO[R & CheckpointStore, GraphError[E], O] =
    run(n.left, input, runId, attempt).flatMap(mid => run(n.right, mid, runId, attempt))

  private def effect[R, E, I, O](
      n: Node.Effect[R, E, I, O],
      input: I,
      runId: RunId,
      attempt: Attempt
  ): ZIO[R & CheckpointStore, GraphError[E], O] =
    ZIO.serviceWithZIO[CheckpointStore] { store =>
      store
        .get(runId, n.id, attempt)
        .mapError(GraphError.StoreFailed(n.id, _))
        .flatMap {
          case Some(committed) =>
            // Replay: read the committed value back. The node body does not
            // execute and is not billed again.
            ZIO
              .fromEither(n.outputSchema.fromDynamic(committed.value))
              .mapError(reason =>
                GraphError.StoreFailed(n.id, StoreError.DecodeFailed(runId, n.id, reason))
              )

          case None =>
            for
              outcome <- Cost.ref.locally(Spend.empty)(n.run(input).either <*> Cost.ref.get)
              (rawResult, spend) = outcome
              // OPEN: spend on a failed node is not recorded anywhere - a
              // node that billed real tokens and then failed (e.g. a
              // malformed LLM reply) has no checkpoint to attach `spend` to,
              // so it is silently dropped here. See CLAUDE.md, "Open
              // decisions".
              result <- ZIO.fromEither(rawResult).mapError(GraphError.NodeFailed(n.id, _))
              now <- Clock.instant
              _ <- store
                .commit(
                  Checkpoint(
                    runId = runId,
                    nodeId = n.id,
                    attempt = attempt,
                    value = n.outputSchema.toDynamic(result),
                    cost = spend.cost,
                    tokens = spend.tokens,
                    committedAt = now
                  )
                )
                .mapError(GraphError.StoreFailed(n.id, _))
            yield result
        }
    }

  private def notYetInterpreted[E](id: NodeId, when: String): IO[GraphError[E], Nothing] =
    ZIO.die(
      new NotImplementedError(
        s"${id.value} has no interpreter yet - see CLAUDE.md roadmap " + when
      )
    )
