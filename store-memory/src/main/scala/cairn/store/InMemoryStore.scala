package cairn.store

import cairn.*
import zio.*

/**
 * Ref-backed [[CheckpointStore]]. Survives for the life of the process, not beyond it.
 *
 * This is the store the test suite runs against — it enforces the same write-once rule as
 * `store-postgres`, so a replay bug shows up here in milliseconds instead of under testcontainers.
 * It is not a no-op double; for that, `CheckpointStore.none` lives in `core`.
 */
final class InMemoryStore(ref: Ref[Map[InMemoryStore.Key, Checkpoint]]) extends CheckpointStore:
  import InMemoryStore.Key

  def get(runId: RunId, nodeId: NodeId, attempt: Attempt): IO[StoreError, Option[Checkpoint]] =
    ref.get.map(_.get(Key(runId, nodeId, attempt)))

  /**
   * Write-once. `modify` gives the check and the insert as one atomic step, which is what a real
   * backend gets from a unique constraint rather than from a read-then-write.
   */
  def commit(checkpoint: Checkpoint): IO[StoreError, Unit] =
    ref
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
    ref.get.map { committed =>
      Chunk
        .fromIterable(committed.collect { case (k, v) if k.runId == runId => v })
        .sortBy(_.committedAt)
    }

  def delete(runId: RunId): IO[StoreError, Unit] =
    ref.update(_.filterNot { case (k, _) => k.runId == runId })

object InMemoryStore:
  final private[store] case class Key(runId: RunId, nodeId: NodeId, attempt: Attempt)

  val make: UIO[CheckpointStore] =
    Ref.make(Map.empty[Key, Checkpoint]).map(new InMemoryStore(_))

  val layer: ULayer[CheckpointStore] = ZLayer(make)
