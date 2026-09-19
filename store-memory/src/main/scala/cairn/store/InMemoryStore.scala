package cairn.store

import cairn.*
import zio.*

/**
 * Ref-backed [[CheckpointStore]]. Survives for the life of the process, not beyond it.
 *
 * This is the store the test suite runs against — it enforces the same write-once rule as
 * `store-postgres` will, so a replay bug shows up here in milliseconds instead of under
 * testcontainers. It is not a no-op double; for that, `CheckpointStore.none` lives in `core`.
 *
 * Two `Ref`s: checkpoints and attempt records are logically separate concerns (a checkpoint means
 * "final, replayable"; an attempt record means "this much was spent trying," possibly on a failure
 * with no checkpoint at all — see `AttemptRecord`'s doc comment) and are never queried together.
 */
final class InMemoryStore(
    checkpoints: Ref[Map[InMemoryStore.Key, Checkpoint]],
    attempts: Ref[Chunk[AttemptRecord]]
) extends CheckpointStore:
  import InMemoryStore.Key

  def get(runId: RunId, nodeId: NodeId, attempt: Attempt): IO[StoreError, Option[Checkpoint]] =
    checkpoints.get.map(_.get(Key(runId, nodeId, attempt)))

  /**
   * Write-once. `modify` gives the check and the insert as one atomic step, which is what a real
   * backend gets from a unique constraint rather than from a read-then-write.
   */
  def commit(checkpoint: Checkpoint): IO[StoreError, Unit] =
    checkpoints
      .modify { committed =>
        val key = Key(checkpoint.runId, checkpoint.nodeId, checkpoint.attempt)
        if committed.contains(key) then
          (
            Some(
              StoreError.AlreadyCommitted(checkpoint.runId, checkpoint.nodeId, checkpoint.attempt)
            ),
            committed
          )
        else (None, committed.updated(key, checkpoint))
      }
      .flatMap {
        case Some(err) => ZIO.fail(err)
        case None => ZIO.unit
      }

  def list(runId: RunId): IO[StoreError, Chunk[Checkpoint]] =
    checkpoints.get.map { committed =>
      Chunk
        .fromIterable(committed.collect { case (k, v) if k.runId == runId => v })
        .sortBy(c => (c.committedAt, c.nodeId.value, c.attempt.value))
    }

  def delete(runId: RunId): IO[StoreError, Unit] =
    checkpoints.update(_.filterNot { case (k, _) => k.runId == runId }) *>
      attempts.update(_.filterNot(_.runId == runId))

  def recordAttempt(record: AttemptRecord): IO[StoreError, Unit] =
    attempts.update(_ :+ record)

  def listAttempts(runId: RunId): IO[StoreError, Chunk[AttemptRecord]] =
    attempts.get.map(_.filter(_.runId == runId))

object InMemoryStore:
  final private[store] case class Key(runId: RunId, nodeId: NodeId, attempt: Attempt)

  /**
   * Returns `CheckpointStore`, not `InMemoryStore` - deliberately unchanged from before this turn's
   * work. Every existing test does `store <- InMemoryStore.make` then
   * `.provideEnvironment(ZEnvironment(store))` against a `CheckpointStore` requirement, and
   * `ZEnvironment` is invariant: widening this signature to `UIO[InMemoryStore]` would silently
   * infer `ZEnvironment[InMemoryStore]` at every one of those call sites and break them all. Both
   * new methods (`recordAttempt`, `listAttempts`) are on the trait itself, so nothing is lost by
   * keeping this narrow.
   */
  val make: UIO[CheckpointStore] =
    for
      c <- Ref.make(Map.empty[Key, Checkpoint])
      a <- Ref.make(Chunk.empty[AttemptRecord])
    yield new InMemoryStore(c, a)

  val layer: ULayer[CheckpointStore] = ZLayer(make)
