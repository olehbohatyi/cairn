package cairn

/**
 * Identifies a node within a graph. First component of a checkpoint key.
 *
 * Ids are path-qualified before they reach a store (see [[Path]]): `FanOut` branches by position,
 * nested `Loop`/`Verify` bodies by ancestor id. Not fully: two children of one `Seq` sharing a
 * literal id still collide in the checkpoint store and replay each other's output (CLAUDE.md, Open
 * decisions).
 */
opaque type NodeId = String

object NodeId:
  def apply(value: String): NodeId = value
  extension (id: NodeId) def value: String = id

/**
 * Identifies one execution of a graph. **The caller generates this, always.**
 *
 * cairn never mints a RunId, and that is load-bearing rather than an oversight. The RunId is the
 * idempotency key for the entire run: a retried HTTP request, a redelivered Kafka message and a
 * restarted pod must all arrive with the same RunId or they are, correctly, different runs. Only
 * the caller knows which of those it is.
 *
 * Derive it from something the caller already has and that is stable across retries - a claim id, a
 * message key, a request id - not from `UUID.randomUUID()` at the call site, which produces a fresh
 * run on every retry and quietly defeats the whole library.
 */
opaque type RunId = String

object RunId:
  def apply(value: String): RunId = value
  extension (id: RunId) def value: String = id

/**
 * Which iteration of a [[Node.Loop]] produced a checkpoint.
 *
 * Settled deliberately (question 8): `attempt` counts *logical* loop iterations, never physical
 * executions. Crash recovery does not increment it - a resumed run recomputes the same attempt
 * number, finds the checkpoint already there and replays it. That is exactly what distinguishes the
 * two cases:
 *
 *   - crash-retry of attempt 0 -> same key -> checkpoint hit -> replay
 *   - loop retry after a failed `accept` -> attempt 1 -> new key -> executes
 *
 * The consequence is that the loop needs no checkpointed counter of its own. On resume it iterates
 * from 0 again; each completed iteration replays for free, and execution naturally resumes at the
 * first iteration that never committed. The `accept` verdict is checkpointed under a derived id so
 * a resumed loop does not re-run an expensive validation either.
 *
 * Revisit if loops ever need to be resumable at a non-deterministic point.
 */
opaque type Attempt = Int

object Attempt:
  val first: Attempt = 0
  def apply(value: Int): Attempt = value
  extension (a: Attempt)
    def value: Int = a
    def next: Attempt = a + 1
