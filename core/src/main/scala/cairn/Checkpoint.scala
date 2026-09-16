package cairn

import zio.*
import zio.schema.DynamicValue

import java.time.Instant

/**
 * One committed node result. Keyed by `(runId, nodeId, attempt)` — see CLAUDE.md, "Checkpoint
 * contract".
 *
 * The value is held as a `DynamicValue` rather than JSON so that `core` stays on `zio-schema` alone
 * (invariant #2) — no `zio-schema-json`, no codec dependency. Backends convert to their own wire
 * format: `store-postgres` will render this to JSON, `store-memory` keeps it as is.
 *
 * OPEN (question 3): the value is stored inline, with no size limit. A node that extracts 40 pages
 * of PDF puts tens of megabytes in this field and, later, in a Postgres row. The alternative is a
 * `value: Either[Ref, DynamicValue]` that spills past a threshold to blob storage. Undecided — but
 * note that changing it later is a store migration, not a code change, so it is worth deciding
 * before `store-postgres` lands in Week 4.
 */
final case class Checkpoint(
    runId: RunId,
    nodeId: NodeId,
    attempt: Attempt,
    value: DynamicValue,
    cost: Option[Money],
    tokens: Option[TokenCount],
    committedAt: Instant
)

/**
 * Token accounting, carried alongside a checkpoint so a resumed run can report what the crashed
 * attempt already spent. Populated by the `llm` module; always `None` for plain ZIO nodes.
 */
final case class TokenCount(input: Long, output: Long):
  def total: Long = input + output

/**
 * Failures from a checkpoint backend.
 *
 * No `Throwable` case, per CLAUDE.md invariant #3 — which costs a real stack trace when a JDBC
 * driver throws. TODO(week 4): revisit when `store-postgres` lands; the likely answer is to log the
 * cause at the backend boundary and keep this channel structured.
 */
enum StoreError:
  /**
   * The write-once rule was violated: another worker already committed this key. The loser abandons
   * the run rather than overwriting.
   */
  case AlreadyCommitted(runId: RunId, nodeId: NodeId, attempt: Attempt)

  /**
   * The stored `DynamicValue` did not decode against the node's current schema — almost always a
   * deployed schema change mid-flight.
   */
  case DecodeFailed(runId: RunId, nodeId: NodeId, reason: String)

  case Backend(message: String)

/**
 * Four methods. Writing one for your own store should be an afternoon — that promise is in the
 * README, so keep this trait small.
 */
trait CheckpointStore:
  def get(runId: RunId, nodeId: NodeId, attempt: Attempt): IO[StoreError, Option[Checkpoint]]

  /**
   * Write-once. Must fail with [[StoreError.AlreadyCommitted]] rather than overwrite — this is what
   * makes replay and concurrent pickup safe.
   */
  def commit(checkpoint: Checkpoint): IO[StoreError, Unit]

  def list(runId: RunId): IO[StoreError, Chunk[Checkpoint]]

  def delete(runId: RunId): IO[StoreError, Unit]

object CheckpointStore:

  /**
   * Commits nothing, remembers nothing, so every node runs every time. For exercising graph
   * structure without persistence — not a test double for replay behaviour, which is what
   * `cairn-store-memory` is for.
   */
  val none: CheckpointStore = new CheckpointStore:
    def get(runId: RunId, nodeId: NodeId, attempt: Attempt): IO[StoreError, Option[Checkpoint]] =
      ZIO.none
    def commit(checkpoint: Checkpoint): IO[StoreError, Unit] = ZIO.unit
    def list(runId: RunId): IO[StoreError, Chunk[Checkpoint]] = ZIO.succeed(Chunk.empty)
    def delete(runId: RunId): IO[StoreError, Unit] = ZIO.unit

  val noneLayer: ULayer[CheckpointStore] = ZLayer.succeed(none)
