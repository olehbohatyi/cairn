package cairn

import zio.*
import zio.schema.DynamicValue

import java.time.Instant

/**
 * One committed node result. Keyed by `(runId, nodeId, attempt)` — see CLAUDE.md, "Checkpoint
 * contract". `nodeId` here is already path-qualified by the interpreter (see [[Path]]) before it
 * reaches the store; the store itself has no idea qualification exists.
 *
 * The value is held as a `DynamicValue` rather than JSON so that `core` stays on `zio-schema` alone
 * (invariant #2) — no `zio-schema-json`, no codec dependency. Backends convert to their own wire
 * format: `store-postgres` will render this to JSON, `store-memory` keeps it as is.
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
 * A record of what one execution *attempt* of a node cost, kept even when the attempt failed and
 * therefore produced no [[Checkpoint]] to attach spend to. Closes the "spend on a failed node is
 * silently dropped" gap (CLAUDE.md, Open decisions) — this is a pure audit trail, never read by
 * `Interpreter` and never replayed; a checkpoint means "this output is final and safe to replay,"
 * an attempt record means "this much was spent trying," and conflating the two would let a failed
 * attempt's leftover data be replayed as if it had succeeded.
 */
final case class AttemptRecord(
    runId: RunId,
    nodeId: NodeId,
    attempt: Attempt,
    outcome: AttemptRecord.Outcome,
    cost: Option[Money],
    tokens: Option[TokenCount],
    recordedAt: Instant
)

object AttemptRecord:
  enum Outcome:
    case Succeeded, Failed

/**
 * Conservative size guard for [[Checkpoint.value]] — CLAUDE.md, "Open decisions", blob-sized
 * checkpoints.
 */
object CheckpointSize:
  val MaxApproxBytes: Long = 256 * 1024L // 256 KB

  /**
   * `toString.length` on the `DynamicValue`, not an actual encoding. `core` has no serializer to
   * call — `zio-schema-json` is a dependency of `llm` and `store-postgres`, not of `core`
   * (invariant #2) — so this is a crude proxy, not a byte-accurate count. It undercounts values
   * with heavy escaping and overcounts values with none, but it is monotonic enough to catch
   * "someone checkpointed forty pages of extracted PDF text" without pulling a codec into `core`
   * just to measure a size.
   */
  def approxBytes(value: DynamicValue): Long = value.toString.length.toLong

/**
 * Failures from a checkpoint backend.
 *
 * No `Throwable` case, per CLAUDE.md invariant #3 — which costs a real stack trace when a JDBC
 * driver throws. `store-postgres` settles that as anticipated: the cause is logged with its stack
 * trace at the backend boundary (`PostgresStore.typed`) and this channel stays structured.
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

  /**
   * A node's output exceeded [[CheckpointSize.MaxApproxBytes]]. The interpreter rejects the commit
   * before it ever reaches a store — no backend is asked to accept an oversized row, so this exists
   * on the shared error type rather than as a `store-postgres`-specific concern.
   */
  case ValueTooLarge(runId: RunId, nodeId: NodeId, approxBytes: Long, ceilingBytes: Long)

  case Backend(message: String)

/**
 * Six methods now, not four - `recordAttempt` and `listAttempts` were added alongside the
 * failed-node-spend fix. The README's "four methods, an afternoon" line was updated to match in the
 * same change, per CLAUDE.md's own stated policy against letting documentation drift.
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

  /**
   * Records what one execution attempt cost, independent of whether it succeeded. See
   * [[AttemptRecord]].
   */
  def recordAttempt(record: AttemptRecord): IO[StoreError, Unit]

  def listAttempts(runId: RunId): IO[StoreError, Chunk[AttemptRecord]]

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
    def recordAttempt(record: AttemptRecord): IO[StoreError, Unit] = ZIO.unit
    def listAttempts(runId: RunId): IO[StoreError, Chunk[AttemptRecord]] =
      ZIO.succeed(Chunk.empty)

  val noneLayer: ULayer[CheckpointStore] = ZLayer.succeed(none)
