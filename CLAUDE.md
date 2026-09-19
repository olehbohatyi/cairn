# CLAUDE.md

Standing instructions for this repository. Read fully before the first edit of a session.

## What this is

A typed, durable orchestration library for LLM workflows on ZIO 2 / Scala 3.

Describe a multi-step AI process as a graph of typed nodes; get crash recovery,
cost ceilings, fail-closed verification, human approval gates and an audit trail
without writing any of that per project.

Not a general durable-execution engine. `dbos4s` already binds the Java DBOS
transact library and we do not compete with it — durability is a pluggable
backend and DBOS is one of the implementations. Our layer is the agent graph:
typed orchestration, budget, tiering, verification.

Use it when more than two LLM calls must happen in a defined order and a crash
halfway through causes a real problem. Below that threshold plain ZIO is correct
and we say so in the README.

## The one decision everything follows from

**The graph is a value, not a function.** A GADT describing nodes and edges,
interpreted separately.

This buys four things a function-based DSL cannot give: static validation at
startup, a serializable graph for tooling, deterministic replay, and
cross-cutting concerns as interpreters that wrap any graph without node code
knowing about them.

Reject any design that makes the graph opaque — no `I => ZIO[R, E, O]` hiding
structure, no builder that erases node identity.

## Public API shape

Target ergonomics. Written before implementation; treat as the contract.

```scala
val claim =
  AgentGraph[ClaimRequest, Decision]
    .node("extract")(Llm.haiku.structured[ClaimData])
    .fanOut("fraud", "estimate")(FraudCheck.run, Estimator.run)
    .join("decide")(Decision.combine)
    .verify("audit", freshContext = true)(Verdict.defaultFail)
    .approvalGate(when = _.amount > Money.eur(5000))
    .withBudget(Budget.eur(0.40))
    .withCheckpoints(Dbos.backend)      // or InMemory.backend in tests

claim.run(request): ZIO[R, GraphError[E], Either[Suspended, Decision]]
Graph.resume(runId, Approval.Granted)   // same path as crash recovery
```

## Core ADT

Six cases. Adding a seventh touches every interpreter — resist it. `retry`,
`timeout`, `cache` and similar are interpreters over `Effect`, not constructors.

Invariant in all four parameters. `O` appears in result position (`Effect`)
and argument position (`Loop.accept`, `Gate.when`), so no consistent variance
annotation exists — `[-R, +E, -I, +O]` does not compile.

`Effect` carries its own `Schema[O]`: you cannot checkpoint a value you cannot
encode, and the node producing the value is where that belongs. The
consequence is a rule worth holding onto — **checkpoints are taken at Effect
boundaries only.** `Seq` returns its right child's output and `FanOut` joins
its branches', and both are already covered by their leaves.

```scala
sealed trait Node[R, E, I, O]:
  def id: NodeId

case class Effect [R,E,I,O](id: NodeId, run: I => ZIO[R,E,O], outputSchema: Schema[O])
case class Seq    [R,E,I,M,O](left: Node[R,E,I,M], right: Node[R,E,M,O])
case class FanOut [R,E,I,O](id: NodeId, branches: NonEmptyChunk[Node[R,E,I,?]],
                            join: Chunk[Any] => O)
case class Loop   [R,E,I,O](id: NodeId, body: Node[R,E,(I, Option[Feedback]), O],
                            accept: O => ZIO[R,E,Boolean], max: Int)
case class Verify [R,E,I,O](id: NodeId, inner: Node[R,E,I,O], judge: Judge[R,E,O])
case class Gate   [R,E,I,O](id: NodeId, inner: Node[R,E,I,O], when: O => Boolean)
```

`FanOut`'s existential branch type is the one real fight with the compiler.
`Chunk[Any]` plus a typed join function is the accepted escape hatch and stays
private to the interpreter — it must never appear in a user-facing signature.

## Error model

```scala
enum GraphError[+E]:
  case NodeFailed(nodeId: NodeId, error: E)
  case BudgetExceeded(spent: Money, ceiling: Money)
  case VerificationFailed(nodeId: NodeId, reason: String)
  case Exhausted(nodeId: NodeId, attempts: Int)
  case StoreFailed(nodeId: NodeId, error: StoreError)
  case JudgeTimedOut(nodeId: NodeId, timeout: Duration)
```

`JudgeTimedOut` is a sixth case, added when `Verify` gained an optional
`timeout`. Distinct from `NodeFailed` because the judge's own effect never
actually failed — it simply didn't finish — so there is no `E` value to carry.

`StoreFailed` is a fifth case, added when checkpointing landed. A backend
failure is genuinely not a node failure — the node may never have run, or may
have run and succeeded but failed to commit. Folding it into `NodeFailed`
would tell the caller something untrue.

`Suspended` is **not** an error. It is a success value carrying the run id and
the pending approval, hence `Either[Suspended, O]` in the success channel.
Getting this wrong forces a painful refactor later.

## Checkpoint contract

One row per `(runId, nodeId, attempt)`: the node output encoded via its
`Schema`, plus cost, token counts and status.

- **Write-once, never upsert.** A collision means a concurrent worker took the
  same run; the loser abandons.
- **The caller supplies `runId`. cairn never generates one.** It is the
  idempotency key for the whole run: a retried request, a redelivered message
  and a restarted pod must all arrive with the same id or they are, correctly,
  different runs. Derive it from something stable — a claim id, a message key —
  never `UUID.randomUUID()` at the call site.
- **`attempt` counts logical loop iterations, never physical executions.**
  Crash recovery does not increment it: a resumed run recomputes the same
  attempt number, finds the checkpoint and replays. That is what separates the
  two cases — crash-retry of attempt 0 hits the same key and replays, while a
  loop retry moves to attempt 1 and executes. A loop therefore needs no
  checkpointed counter of its own.
- "Replayed" means the committed value is read back. The node body does not run
  again and is not billed again.
- `zio-schema` derivation gives both the checkpoint codec and the JSON Schema
  sent to the model's structured-output API from one `derives Schema`. There is
  no second serialization story — do not introduce one.

## Determinism boundary

Node *bodies* may be non-deterministic and do I/O. That is the point.

The graph *structure* must be a function of checkpointed state only. Branch on a
committed node output, never on the clock, a fresh UUID or ambient config.
Enforce structurally: `Gate`'s predicate takes the previous node's output and
nothing else, so there is no ergonomic way to reach for `Instant.now`.

## Known open window

A node whose side effect landed but whose checkpoint did not. The library cannot
close this — the email provider is not in our transaction. Mitigation: every
`Effect` receives an idempotency key derived from `(runId, nodeId, attempt)`,
easy to pass through to Stripe, SES or an upsert. Document this prominently;
the honest treatment is a credibility signal.

## Modules

`core` must not depend on any LLM or database library.

```
core           graph ADT, interpreter, budget, GraphError
llm            LlmClient, structured outputs, tiering
store-memory   in-memory checkpoints
store-postgres plain JDBC + HikariCP (see Stack note)
store-dbos     adapter over dbos4s
testkit        stubbed LLM, recorded fixtures, graph assertions
examples       runnable, scala-cli friendly
```

`llm` went through two drafted variants before merge; one was selected and the
other is not kept anywhere, including in git history. **v2 was selected over
v1: fewer files, one shared `node` constructor instead of duplicated
request-building logic per method, and a seven-case `LlmError` trimmed to
five** (`RateLimited`/`Overloaded`/`Transport` collapsed into `Retryable`,
since a caller does the same thing with all three: retry; `InvalidRequest`
and `UnexpectedStatus` both fell into a generic `Rejected` — v1's distinct
`Provider` enum and `Chunk[Message]` multi-turn history were either unread
anywhere or speculative for a library where every node is a fresh call by
design). v2's own regression — a dropped `stopReason` on `LlmResponse`, which
made a `max_tokens` truncation indistinguishable from ordinary malformed
output — was restored as part of the merge, along with a `status: Int`-
carrying `UnexpectedStatus` case, now a distinct fifth `LlmError` case rather
than folded into `Rejected`. v1 is rejected, not deferred.

## Stack

Versions verified 2026-09-15. Pin them in `project/Dependencies.scala`; do not
bump anything in this file without checking the notes below first.

### Toolchain

| | Version | Note |
|---|---|---|
| Scala | `3.9.0` | LTS, released 2026-09-03, succeeds 3.3 LTS |
| sbt | `2.0.9` | sbt 2 line |
| JDK | 21 or 25 | target `-release 17` |

No 2.13 cross-build in 0.x. Inline and macros are needed for structured-output
derivation, and cross-building doubles CI for users we do not have yet.

**Publishing reach, decided deliberately:** artifacts built with 3.9 cannot be
consumed by Scala 3.3 projects, and roughly 56% of Scala 3 libraries are still
published against 3.3 LTS. Building on 3.9.0 means anyone still on 3.3.x cannot
use us. That is accepted for 0.x — early adopters track LTS moves. Revisit
before 1.0; if reach matters more than language features by then, the fallback
is to publish from 3.3.8, which every 3.3-through-3.9 consumer can read.

### Libraries

| | Version | Note |
|---|---|---|
| `dev.zio::zio`, `zio-streams`, `zio-test` | `2.1.26` | |
| `dev.zio::zio-schema`, `zio-schema-json` | `1.8.7` | checkpoint codecs + JSON Schema |
| `dev.zio::zio-http` | `3.3.3` | `llm`'s Anthropic transport |
| `dev.zio::zio-opentelemetry` | `3.1.18` | 4.0.0 is still RC12; stay on 3.1.x |
| dbos4s | resolve latest | `store-dbos` only |
| testcontainers-scala | resolve latest | `store-postgres` tests |

Entries marked *resolve latest* were not pinned at the time of writing — check
Maven Central and fix the version before first use. Do not guess.

**`zio-jdbc` and `zio-sql` are deprecated and moved to `zio-archive`.** Do not
add either. The checkpoint table is one write-once table with four queries, so
`store-postgres` uses plain JDBC over a HikariCP pool wrapped in a `ZLayer`.
That keeps the module dependency-light and avoids betting on a persistence
library at all. If typed queries ever become worth it, Quill
(`quill-jdbc-zio`) is the only actively maintained RDBMS option in the ZIO
ecosystem — but the bar for adding it is high.

Watch but do not adopt: `zio-blocks` (0.0.x) is a new ZIO umbrella covering
schema, JSON and streams work that may eventually supersede `zio-schema`. Far
too early to depend on.

### Build plugins

| | Version | sbt 2 |
|---|---|---|
| `sbt-ci-release` | `1.11.2` | yes |
| `sbt-scalafix` | `0.14.6` | yes, `sbt2_3` artifact |
| `sbt-scalafmt` | `2.6.1` | yes |
| `scalafmt-core` | `3.11.1` | |
| `sbt-mdoc` | `2.9.2` | yes, since 2.8.0 |
| `sbt-mima-plugin` | `1.1.6` | **no** — sbt 2 support is still an open PR |

MiMa having no sbt 2 release costs us nothing: binary-compatibility checking is
deferred past 1.0 anyway. If any other plugin turns out to lack an sbt 2
artifact, drop the plugin rather than downgrading the build to sbt 1.

## Invariants

Violations of these are review-blocking regardless of how the code reads.

1. The graph is data. Never a function that hides structure.
2. `core` imports nothing beyond `zio` and `zio-schema`. No transport library
   (`zio-http` today) may appear in a public signature — it lives behind our
   own `LlmClient` trait.
3. Every public failure is a case in `GraphError`. No leaked exceptions, no
   `Throwable` in a user-facing error channel.
4. No test makes a network call. Ever. Use recorded fixtures.
5. Verification is fail-closed. Anything other than an explicit pass is a
   failure; there is no path to success that bypasses a `Verify` node.
6. `Suspended` holds no fiber, no thread and no memory. State is on disk.
7. Prefer a deterministic code verifier over an LLM judge wherever the check can
   be expressed in Scala.
8. Cost aggregation is store summation, never live FiberRef propagation. This
   holds only because every `Node.Effect` execution clears its `Cost.ref` via
   `locally`, without exception — see `Cost.scala`.

## Test strategy

Build the recording layer early — before it exists, tests either cost money or
are nondeterministic, and they will not get written.

`RecordingLlmClient` writes request/response pairs to JSON on first run and
replays after. The full suite runs offline in seconds. This doubles as a
shipped feature: users want it too.

Crash-recovery tests kill the interpreter mid-graph under testcontainers
Postgres and assert that committed nodes are not re-executed.

## Roadmap

| Week | Deliverable |
|------|-------------|
| 1 | Graph ADT, interpreter, `Seq`/`FanOut`/join, in-memory store |
| 2 | `LlmClient`, structured outputs via zio-schema, tiering, budget interpreter |
| 3 | `Verify` / fail-closed, `Loop` / `onExhausted` |
| 4 | Postgres store, crash-recovery tests under testcontainers |
| 5 | Suspension and approval gates, resume API |
| 6 | One full example, docs site, publish `0.1.0` |

`testkit` + recorded fixtures did not fit Week 3 alongside `Verify` and `Loop`.
Parked as its own unit of work rather than wedged into a week it doesn't fit —
real, owed scope (a recording `LlmClient` the README promises, and the natural
place to eventually cover `Anthropic.decode`'s untested status-match arms, see
Open decisions), not dropped. Not yet assigned a week.

Week 4 is the one that slips. If motivation stalls there, the accepted fallback
is to cut our own Postgres store from v1 and ship with `dbos4s` as the only
durable backend — differentiator kept, two weeks saved.

Deferred past 0.1.0: Kafka/streams integration, the replay console UI, MCP
server, gRPC, binary-compatibility checks. Stay on 0.x and state that the API
is unstable; MiMa this early only slows things down.

## Reference scenarios

Three worked scenarios drive API design. When an ergonomic question comes up,
resolve it against these rather than in the abstract.

1. **Insurance claims.** Extract → parallel fraud + estimate → decide → audit →
   approval gate above €5000. Exercises fan-out, verification, suspension.
2. **Nightly partner feeds.** 60 feeds, schema drift detection, an LLM repair
   loop with a *deterministic* validator (apply the mapping to ten rows and
   check they parse), quarantine on exhaustion, 8 in parallel, per-feed budget.
   Exercises loops, code verifiers, exactly-once writes.
3. **Security alert triage.** Kafka stream, dedup by fingerprint, tiered
   escalation haiku → sonnet → human, side effects gated by approval, fail-closed
   so no alert is silently closed. Exercises streaming, tiering, guarded actions.

Build the CI-reviewer variant of (1) first — it can run against our own PRs from
week 3, and real usage reshapes the API faster than design discussion.

## Dogfooding

The library's own CI runs an agent graph over incoming PRs: four parallel
reviewers (security, API ergonomics, docs-sync, coverage) into an aggregator,
then an asymmetric verifier with fresh context and a fail-closed verdict.
Reuse the calibration work from `agent-patterns-lab` rather than redoing it.

The verdict parser must key on an explicit `VERDICT:` marker, never on the
judge's first word — that bug silently broke the fail-safe default once already.

## Working conventions

- Conventional Commits. `feat`, `fix`, `chore`, `docs`, `refactor`, `test`.
- Documentation is signal only. No boilerplate, no restating what the code says,
  no "in this section we will". Trim aggressively.
- All mdoc snippets must compile. A doc example that does not compile is a bug.
- README-driven: when adding a feature, write its README paragraph and example
  first, then implement. If the example is awkward to write, the API is wrong.
- Do not add a dependency to `core` without asking.
- Do not invent a new node constructor without asking.

## Commands

```
sbt lint          scalafmtCheck + scalafix --check
sbt testAll       unit + integration (needs Docker)
sbt testFast      unit only, offline, no Docker
sbt docs/mdoc     build and typecheck documentation
```

## Open decisions

Recorded at the code that would change, not just here.

- **Blob-sized checkpoint values: FIXED (Week 4), approximately.** `Checkpoint`
  commits now reject anything over `CheckpointSize.MaxApproxBytes` (256 KB)
  with a typed `StoreError.ValueTooLarge`, checked via `approxBytes` — a
  `toString.length` proxy, not a byte-accurate encoding, since `core` has no
  serializer to measure real bytes (invariant #2: no `zio-schema-json` in
  `core`). Catches the "silently wrote a 40MB row" failure mode; does not
  implement full blob-storage spillover, which is still real, undone scope if
  256 KB turns out too small for a real workload.
- **Node id collisions: NARROWED (Week 4), not closed.** `Path` qualifies
  `FanOut` branches by position and disambiguates nested `Loop`/`Verify`
  bodies by ancestor id before hitting the store — the two collision shapes
  named when this gap was first recorded. `Seq`'s `left`/`right` still share
  one path and are NOT disambiguated: two children of one `Seq` sharing a
  literal id still collide. A future reader should not assume this is fully
  solved.
- **Fan-out failure mode.** `foreachPar` is fail-fast: one branch failing
  interrupts its siblings, discarding LLM calls already paid for and leaving a
  partially checkpointed fan-out. Safe on resume, not free. The alternative is
  to let all branches finish and collect failures. One call site either way.
- **Spend on a failed node is not recorded anywhere: FIXED (Week 4).** A
  distinct `AttemptRecord` (not a `Checkpoint` — a checkpoint means "final and
  safe to replay," an attempt record means "this much was spent trying," and
  conflating them would let a failed attempt's data get replayed as if it had
  succeeded) is written via `CheckpointStore.recordAttempt` from `effect`'s
  failure branch, best-effort (`.ignore`d — a store hiccup while auditing a
  failure must never mask the real failure). `listAttempts` reads them back;
  nothing in `Interpreter` reads them itself yet — this is a pure audit trail,
  not consumed by replay logic. `CheckpointStore` is six methods now, not
  four; the README's "four methods, an afternoon" line has been updated to
  match.
- **`Spend` accumulation assumes single-currency pricing: crash FIXED, design
  deferred.** `Money.+` and `Spend.+` return `Either[CurrencyMismatch, _]`,
  and `Cost.report` is `IO[CurrencyMismatch, Unit]`, so a mixed-currency report
  is a typed failure instead of a dead fiber (invariant #3). The node body
  chooses what a mismatch means in its own error type: `Llm.node` maps it to
  `LlmError.Rejected`; a `CurrencyMismatch` in a custom `E` surfaces as
  `NodeFailed`. On a mismatch the accumulator is left unchanged, so the
  mismatching spend is recorded nowhere, not even as an `AttemptRecord`.
  Still open, on purpose: `Money.>` still `require`s matching currencies and
  throws. Nothing calls it yet, and its natural caller is the budget
  interpreter's ceiling check, where its shape gets decided. The real design
  question is also open: whether the interpreter enforces one currency per
  graph at construction time, using the static-validation property the graph
  design exists to give. Deferred to the budget interpreter, which was named in
  Week 1's "what wraps the graph" list and is still unbuilt. The live version
  of this problem was the README's own pitch: `Model` prices in `"USD"` and the
  example budgeted in EUR. The example now says `Budget.usd`. `store-postgres`
  stores `Money` as `(cost_cents BIGINT, currency CHAR(3))`, not a fixed-currency
  column, so a second currency later is not a migration.
- **`Anthropic`'s transport layer is untested — not just `UnexpectedStatus`.**
  `StructuredNodeSpec` stubs `LlmClient` directly and never constructs an
  `Anthropic` or reaches `decode`/`parse`, so the entire status match
  (`401`/`403`, `400`, `429`, `5xx`, and the `200` branch's actual HTTP
  round-trip and JSON extraction) is equally unreached by anything in this
  module — `UnexpectedStatus` just happens to be the case that was added most
  recently, not the one gap. This is a scope boundary, not a bug: needs a fake
  `Client`, not a fake `LlmClient`, which is a `cairn-testkit` job. Related but
  distinct: `Llm.node`'s truncation-detecting `mapError` has a `case other =>
  other` fallthrough that is dead code today, not defensive code that is
  merely unexercised — `structured`'s and `text`'s `decode` closures only ever
  produce `Malformed` or succeed, so nothing currently reaches that arm. Fine
  to keep for a future error shape; "correct but currently unreachable" is the
  honest label, not "defensive."
- **`JsonSchema`'s permissive fallback.** Sum types with data-carrying cases,
  `Either`, tuples, and schemas recursing past the first `Lazy` unwrapping all
  render as a permissive `{}` rather than failing derivation. Right while the
  surface is small — a loose schema still produces usable output, where a hard
  failure would block a node that would otherwise work — but it will start
  hiding bugs once someone models a real domain. Revisit whether an
  unsupported construct should instead be a compile-time error.
- **`cairn-store-dbos` positioning.** Dropping it for dependency purity also
  drops the answer to "why not just use Temporal's Java SDK". Not a dependency
  decision, a positioning one — unresolved.
- **`sttp-ai` is a neighbour, not just a dependency declined.** At 0.11.0 it
  ships structured outputs, tool calling, an agent loop, and an
  `agent-testkit` module that overlaps with the planned `cairn-testkit`. It
  does no durable checkpointing and no graph, so cairn's differentiator
  (durability + the graph ADT) holds — but that's the argument that needs
  making explicitly if the question ever comes up, not an assumption to leave
  unstated.
- **`Verify`'s judge verdict is not checkpointed: FIXED (Week 4).**
  `Interpreter.checkedJudge` checkpoints the verdict under a path-qualified
  derived id (`"<verifyId>/judge"`), mirroring `checkedAccept` exactly. A
  crash after a `Verify` node has already passed now replays the verdict on
  resume instead of re-invoking the judge from scratch — verified by the
  updated replay test in `ReplaySpec` (previously asserted `judgeRuns == 2`,
  proving the gap; now asserts `judgeRuns == 1`, proving the fix) and by
  `NestedPathSpec`, which confirms the same qualification works correctly
  when `Verify` sits inside a non-root path handed down from an enclosing
  `FanOut` branch, not just at the graph root.
- **`Verify` has no timeout: FIXED (Week 4).** `Node.Verify` gained an
  additive `timeout: Option[Duration] = None` field — every existing
  `Node.Verify(id, inner, judge)` call site is unaffected by the default. When
  set, `checkedJudge` bounds the judge call with `.timeout(d)` and fails with
  the new `GraphError.JudgeTimedOut(nodeId, timeout)` rather than hanging the
  run; when unset, behavior is unchanged from before. Timeout handling only
  applies on a cache miss — a replayed verdict is instant, so there is nothing
  to bound. Verified with `TestClock` (fork, adjust, join), not real sleeps,
  in `VerifyTimeoutSpec`.
- **Neither `Loop.accept` nor `Verify.judge` scope `Cost.ref` around their
  invocation.** `effect` runs the node body inside `Cost.ref.locally(Spend.empty)(...)`
  and reads the accumulated `Spend` back out to attach to the checkpoint;
  `checkedAccept`'s call to `n.accept(output)` and `checkedJudge`'s call to
  `n.judge.check(output)` do neither — both hardcode `cost = None, tokens =
  None` unconditionally, even now that `checkedJudge` commits a real
  `Checkpoint` for the verdict (Week 4) the same way `checkedAccept` always
  has. A future LLM-based `accept` or `judge` (the README's whole
  propose/validate pitch for `Loop`, and the fresh-context second-model pitch
  for `Verify`, both point straight at this) would report spend into the
  ambient `FiberRef` with nothing scoped to read it back — silently
  uncounted, not merely unattributed, and worse than the already-fixed
  failed-node-spend gap because this happens on an outright success with a
  `cost` field sitting right there on the checkpoint, unused. Dormant today
  because every `accept` and `judge` in this codebase is code-only. Fix is a
  real design question — does `checkedAccept`/`checkedJudge` get their own
  `locally`/`Cost.ref.get` pair mirroring `effect`'s — not done here.
