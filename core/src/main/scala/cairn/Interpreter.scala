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

  private val BooleanSchema: Schema[Boolean] = Schema.primitive[Boolean]

  /**
   * The caller supplies `runId`; cairn never generates one. See [[RunId]] for why that is
   * load-bearing rather than an inconvenience.
   *
   * `attempt` starts at [[Attempt.first]] and is advanced only by [[Node.Loop]], never by crash
   * recovery. `path` starts at [[Path.root]] - see `Path`'s doc comment for what it does and does
   * not disambiguate.
   */
  def run[R, E, I, O](
      node: Node[R, E, I, O],
      input: I,
      runId: RunId
  ): ZIO[R & CheckpointStore, GraphError[E], O] =
    run(node, input, runId, Attempt.first, Path.root)

  private def run[R, E, I, O](
      node: Node[R, E, I, O],
      input: I,
      runId: RunId,
      attempt: Attempt,
      path: Path
  ): ZIO[R & CheckpointStore, GraphError[E], O] =
    node match
      case n: Node.Effect[R, E, I, O] =>
        effect(n, input, runId, attempt, path)

      case n: Node.Seq[R, E, I, ?, O] =>
        runSeq(n, input, runId, attempt, path)

      case n: Node.FanOut[R, E, I, O] =>
        fanOut(n, input, runId, attempt, path)

      case n: Node.Loop[R, E, I, O] =>
        iterate(n, input, None, runId, attempt, path)

      case n: Node.Verify[R, E, I, O] =>
        verify(n, input, runId, attempt, path)

      case n: Node.Gate[R, E, I, O] =>
        notYetInterpreted(n.id, "Week 5")

  /**
   * OPEN (question 5): `foreachPar` is fail-fast — undecided, see CLAUDE.md. Unchanged by this
   * turn's work except that each branch now descends a positional path segment (`"<id>#<index>"`),
   * so two branches sharing a literal `NodeId` land at distinct store keys instead of colliding.
   * See the new "duplicate branch id" test in `store-memory`.
   */
  private def fanOut[R, E, I, O](
      n: Node.FanOut[R, E, I, O],
      input: I,
      runId: RunId,
      attempt: Attempt,
      path: Path
  ): ZIO[R & CheckpointStore, GraphError[E], O] =
    ZIO
      .foreachPar(Chunk.fromIterable(n.branches).zipWithIndex) { case (branch, i) =>
        run(branch, input, runId, attempt, path.descend(s"${n.id.value}#$i"))
      }
      .map(results => n.join(results))

  /**
   * Split out so the existential middle type `M` is bound by the method's own type parameter rather
   * than inferred at the match site. `Seq` does not descend a path segment - see `Path`'s doc
   * comment for why that is a known, narrower gap.
   */
  private def runSeq[R, E, I, M, O](
      n: Node.Seq[R, E, I, M, O],
      input: I,
      runId: RunId,
      attempt: Attempt,
      path: Path
  ): ZIO[R & CheckpointStore, GraphError[E], O] =
    run(n.left, input, runId, attempt, path).flatMap(mid => run(n.right, mid, runId, attempt, path))

  private def effect[R, E, I, O](
      n: Node.Effect[R, E, I, O],
      input: I,
      runId: RunId,
      attempt: Attempt,
      path: Path
  ): ZIO[R & CheckpointStore, GraphError[E], O] =
    val checkpointId = path.qualify(n.id)
    ZIO.serviceWithZIO[CheckpointStore] { store =>
      store
        .get(runId, checkpointId, attempt)
        .mapError(GraphError.StoreFailed(n.id, _))
        .flatMap {
          case Some(committed) =>
            // Replay: read the committed value back. The node body does not
            // execute and is not billed again.
            ZIO
              .fromEither(n.outputSchema.fromDynamic(committed.value))
              .mapError(reason =>
                GraphError.StoreFailed(n.id, StoreError.DecodeFailed(runId, checkpointId, reason))
              )

          case None =>
            for
              outcome <- Cost.ref.locally(Spend.empty)(n.run(input).either <*> Cost.ref.get)
              (rawResult, spend) = outcome
              now <- Clock.instant
              result <- rawResult match
                case Left(e) =>
                  // Best-effort: a store hiccup while recording a failed
                  // attempt must never mask the real failure it's trying to
                  // record. `.ignore` discards any recordAttempt error.
                  recordFailedAttempt(store, runId, n.id, attempt, spend, now) *>
                    ZIO.fail(GraphError.NodeFailed(n.id, e))

                case Right(value) =>
                  val dynamic = n.outputSchema.toDynamic(value)
                  val bytes = CheckpointSize.approxBytes(dynamic)
                  if bytes > CheckpointSize.MaxApproxBytes then
                    // The computation succeeded and billed real tokens, but
                    // nothing usable can be committed - treat it the same
                    // as a failure for audit purposes so the spend is not
                    // silently lost a second way.
                    recordFailedAttempt(store, runId, n.id, attempt, spend, now) *>
                      ZIO.fail(
                        GraphError.StoreFailed(
                          n.id,
                          StoreError.ValueTooLarge(
                            runId,
                            checkpointId,
                            bytes,
                            CheckpointSize.MaxApproxBytes
                          )
                        )
                      )
                  else
                    store
                      .commit(
                        Checkpoint(
                          runId = runId,
                          nodeId = checkpointId,
                          attempt = attempt,
                          value = dynamic,
                          cost = spend.cost,
                          tokens = spend.tokens,
                          committedAt = now
                        )
                      )
                      .mapError(GraphError.StoreFailed(n.id, _))
                      .as(value)
            yield result
        }
    }

  private def recordFailedAttempt[E](
      store: CheckpointStore,
      runId: RunId,
      nodeId: NodeId,
      attempt: Attempt,
      spend: Spend,
      now: java.time.Instant
  ): UIO[Unit] =
    store
      .recordAttempt(
        AttemptRecord(
          runId,
          nodeId,
          attempt,
          AttemptRecord.Outcome.Failed,
          spend.cost,
          spend.tokens,
          now
        )
      )
      .ignore

  /**
   * Fail-closed per CLAUDE.md invariant #5: the only path to success is an explicit `true` from the
   * judge. A judge whose own effect fails is `.mapError`'d into `NodeFailed`; a judge that dies
   * propagates as a defect, untouched; a judge that runs past `n.timeout` (when set) fails with
   * `GraphError.JudgeTimedOut`, never with a silently-assumed `true`. There is no `.orElse` or
   * `.catchAll` anywhere in this method or in `checkedJudge` - no code path exists through which a
   * judge-infrastructure failure, death, or timeout can be converted into a pass.
   *
   * `inner`'s own failure is not touched here at all - `run(n.inner, ...)` already returns
   * `GraphError[E]`, with `inner`'s own id already attached by whichever leaf actually failed, and
   * flows straight out via ordinary short-circuiting.
   */
  private def verify[R, E, I, O](
      n: Node.Verify[R, E, I, O],
      input: I,
      runId: RunId,
      attempt: Attempt,
      path: Path
  ): ZIO[R & CheckpointStore, GraphError[E], O] =
    for
      output <- run(n.inner, input, runId, attempt, path.descend(n.id.value))
      verdict <- checkedJudge(n, output, runId, attempt, path)
      result <-
        if verdict then ZIO.succeed(output)
        else ZIO.fail(GraphError.VerificationFailed(n.id, "judge rejected the output"))
    yield result

  /**
   * The judge's verdict, checkpointed under a path-qualified derived id so a resumed run does not
   * re-invoke the judge for a `Verify` that already passed - mirrors `checkedAccept` exactly. This
   * is the fix for the gap `Node.Verify`'s doc comment used to describe as open; see the updated
   * replay test in `store-memory`, which now asserts the judge is called once across two runs
   * rather than once per run.
   *
   * Timeout handling happens only on a cache miss - a replayed verdict is instant, so there is
   * nothing to bound.
   */
  private def checkedJudge[R, E, I, O](
      n: Node.Verify[R, E, I, O],
      output: O,
      runId: RunId,
      attempt: Attempt,
      path: Path
  ): ZIO[R & CheckpointStore, GraphError[E], Boolean] =
    ZIO.serviceWithZIO[CheckpointStore] { store =>
      val judgeId = path.qualify(NodeId(s"${n.id.value}/judge"))
      store
        .get(runId, judgeId, attempt)
        .mapError(GraphError.StoreFailed(n.id, _))
        .flatMap {
          case Some(committed) =>
            ZIO
              .fromEither(BooleanSchema.fromDynamic(committed.value))
              .mapError(reason =>
                GraphError.StoreFailed(n.id, StoreError.DecodeFailed(runId, judgeId, reason))
              )

          case None =>
            for
              verdict <- n.timeout match
                case None =>
                  n.judge.check(output).mapError(GraphError.NodeFailed(n.id, _))
                case Some(d) =>
                  n.judge
                    .check(output)
                    .mapError(GraphError.NodeFailed(n.id, _))
                    .timeout(d)
                    .flatMap {
                      case Some(v) => ZIO.succeed(v)
                      case None => ZIO.fail(GraphError.JudgeTimedOut(n.id, d))
                    }
              now <- Clock.instant
              _ <- store
                .commit(
                  Checkpoint(
                    runId = runId,
                    nodeId = judgeId,
                    attempt = attempt,
                    value = BooleanSchema.toDynamic(verdict),
                    cost = None,
                    tokens = None,
                    committedAt = now
                  )
                )
                .mapError(GraphError.StoreFailed(n.id, _))
            yield verdict
        }
    }

  /**
   * `attempt` is the single counter threaded through the whole `run` call tree; `Loop` is the only
   * case that changes it - unchanged by this turn's work. `path` descends once, with the loop's own
   * id, when recursing into `body`; `checkedAccept` uses the *un-descended* `path` (the loop node's
   * own level), since the accept verdict is a sibling concern of the loop, not a descendant of it.
   */
  private def iterate[R, E, I, O](
      n: Node.Loop[R, E, I, O],
      input: I,
      feedback: Option[Node.Loop.Feedback],
      runId: RunId,
      attempt: Attempt,
      path: Path
  ): ZIO[R & CheckpointStore, GraphError[E], O] =
    for
      output <- run(n.body, (input, feedback), runId, attempt, path.descend(n.id.value))
      verdict <- checkedAccept(n, output, runId, attempt, path)
      result <-
        if verdict then ZIO.succeed(output)
        else if attempt.next.value >= n.max then ZIO.fail(GraphError.Exhausted(n.id, n.max))
        else
          iterate(
            n,
            input,
            Some(Node.Loop.Feedback(s"attempt ${attempt.value} rejected")),
            runId,
            attempt.next,
            path
          )
    yield result

  private def checkedAccept[R, E, I, O](
      n: Node.Loop[R, E, I, O],
      output: O,
      runId: RunId,
      attempt: Attempt,
      path: Path
  ): ZIO[R & CheckpointStore, GraphError[E], Boolean] =
    ZIO.serviceWithZIO[CheckpointStore] { store =>
      val acceptId = path.qualify(NodeId(s"${n.id.value}/accept"))
      store
        .get(runId, acceptId, attempt)
        .mapError(GraphError.StoreFailed(n.id, _))
        .flatMap {
          case Some(committed) =>
            ZIO
              .fromEither(BooleanSchema.fromDynamic(committed.value))
              .mapError(reason =>
                GraphError.StoreFailed(n.id, StoreError.DecodeFailed(runId, acceptId, reason))
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
                .mapError(GraphError.StoreFailed(n.id, _))
            yield verdict
        }
    }

  private def notYetInterpreted[E](id: NodeId, when: String): IO[GraphError[E], Nothing] =
    ZIO.die(
      new NotImplementedError(
        s"${id.value} has no interpreter yet - see CLAUDE.md roadmap " + when
      )
    )
