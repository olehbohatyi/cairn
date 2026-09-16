package cairn

import zio.*
import zio.schema.Schema

/**
 * Executes a graph, committing a checkpoint after every [[Node.Effect]] and replaying any that
 * already committed for this `runId`.
 *
 * This is the only interpreter. Budget, tiering and tracing arrive as layers *over* this one
 * (CLAUDE.md, "What wraps the graph at run time"); running without persistence is
 * `CheckpointStore.none`, not a second implementation.
 *
 * `Gate` is not wired yet (Week 5). Reaching it is a defect, not a silent no-op - see
 * `notYetInterpreted`.
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
        iterate(n, input, None, runId, attempt)

      case n: Node.Verify[R, E, I, O] =>
        verify(n, input, runId, attempt)

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

  /**
   * Fail-closed per CLAUDE.md invariant #5: the only path to success is an explicit `true` from the
   * judge. A judge whose own effect fails - a rate limit, a timeout, malformed judge output,
   * anything the judge's `E` carries - is `.mapError`'d into `NodeFailed`, the same as any other
   * node failure. There is deliberately no `.orElse` or `.catchAll` here: neither appears anywhere
   * in this method, so there is no code path through which a judge-infrastructure failure can be
   * converted into a success.
   *
   * What this does NOT protect against: a `Judge` implementation that catches its own error
   * internally and returns `true` from inside `check`. Fail-closed here is a property of the
   * interpreter's plumbing, not a guarantee about arbitrary `Judge` implementations - see the
   * dogfooding note about keying on an explicit `VERDICT:` marker rather than a judge's first word,
   * which is exactly this failure mode one level up, inside a judge rather than inside the
   * interpreter.
   *
   * A judge that dies (a genuine defect - a thrown exception, not a typed `E` failure) is not
   * caught either. `.mapError` only transforms the `E` channel; a `Cause.Die` propagates through
   * untouched, same as an unimplemented `Loop`/`Gate` node. Dying is not silently passing.
   *
   * No timeout is imposed here. `Node.Verify` has no timeout field, and adding one is an ADT
   * change, not an interpreter one - undecided, see CLAUDE.md open decisions. A judge that never
   * completes hangs the run rather than failing closed after some bound, unless the judge's own
   * implementation imposes a timeout and maps it to a typed `E`.
   */
  private def verify[R, E, I, O](
      n: Node.Verify[R, E, I, O],
      input: I,
      runId: RunId,
      attempt: Attempt
  ): ZIO[R & CheckpointStore, GraphError[E], O] =
    for
      output <- run(n.inner, input, runId, attempt)
      verdict <- n.judge.check(output).mapError(GraphError.NodeFailed(n.id, _))
      result <-
        if verdict then ZIO.succeed(output)
        else ZIO.fail(GraphError.VerificationFailed(n.id, "judge rejected the output"))
    yield result

  private val BooleanSchema: Schema[Boolean] = Schema.primitive[Boolean]

  /**
   * `attempt` is the single counter threaded through the whole `run` call tree; `Loop` is the only
   * case that changes it. On entry (fresh run or crash resume alike) it always starts at whatever
   * `attempt` this `Loop` node itself was reached with - never re-derived or reset by the
   * interpreter - and only this method ever increments it, once per rejection. That is what makes
   * "crash-retry of attempt N replays" and "loop-retry after a rejection moves to attempt N+1 and
   * executes" the same code path rather than two: a resumed run re-enters `iterate` at the same
   * `attempt` it last held, `run(n.body, ...)` and `checkedAccept` both hit their existing
   * checkpoints and replay for free, and execution only does new work at the first attempt that
   * never committed - whether that attempt is "new" because of a rejection or "new" because nothing
   * checkpointed there yet are indistinguishable to this method, deliberately.
   */
  private def iterate[R, E, I, O](
      n: Node.Loop[R, E, I, O],
      input: I,
      feedback: Option[Node.Loop.Feedback],
      runId: RunId,
      attempt: Attempt
  ): ZIO[R & CheckpointStore, GraphError[E], O] =
    for
      output <- run(n.body, (input, feedback), runId, attempt)
      verdict <- checkedAccept(n, output, runId, attempt)
      result <-
        if verdict then ZIO.succeed(output)
        else if attempt.next.value >= n.max then ZIO.fail(GraphError.Exhausted(n.id, n.max))
        else
          iterate(
            n,
            input,
            Some(Node.Loop.Feedback(s"attempt ${attempt.value} rejected")),
            runId,
            attempt.next
          )
    yield result

  /**
   * `accept`'s verdict, checkpointed under a derived id so a resumed loop does not re-run an
   * expensive validation any more than it re-runs `body` - see the `Loop` doc comment. Same shape
   * as [[effect]]: check for an existing checkpoint first, run and commit only on a miss. A failure
   * from `accept` itself carries the *loop's* id, not the derived one - `n.id`, matching how
   * `verify`'s judge failures carry the `Verify` node's id rather than `inner`'s.
   */
  private def checkedAccept[R, E, I, O](
      n: Node.Loop[R, E, I, O],
      output: O,
      runId: RunId,
      attempt: Attempt
  ): ZIO[R & CheckpointStore, GraphError[E], Boolean] =
    ZIO.serviceWithZIO[CheckpointStore] { store =>
      val acceptId = NodeId(s"${n.id.value}/accept")
      store
        .get(runId, acceptId, attempt)
        .mapError(GraphError.StoreFailed(acceptId, _))
        .flatMap {
          case Some(committed) =>
            ZIO
              .fromEither(BooleanSchema.fromDynamic(committed.value))
              .mapError(reason =>
                GraphError.StoreFailed(acceptId, StoreError.DecodeFailed(runId, acceptId, reason))
              )

          case None =>
            for
              verdict <- n.accept(output).mapError(GraphError.NodeFailed(n.id, _))
              now <- Clock.instant
              _ <- store
                .commit(
                  Checkpoint(
                    runId = runId,
                    nodeId = acceptId,
                    attempt = attempt,
                    value = BooleanSchema.toDynamic(verdict),
                    cost = None,
                    tokens = None,
                    committedAt = now
                  )
                )
                .mapError(GraphError.StoreFailed(acceptId, _))
            yield verdict
        }
    }

  private def notYetInterpreted[E](id: NodeId, when: String): IO[GraphError[E], Nothing] =
    ZIO.die(
      new NotImplementedError(
        s"${id.value} has no interpreter yet - see CLAUDE.md roadmap " + when
      )
    )
