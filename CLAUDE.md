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
```

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
| `dev.zio::zio-http` | `3.3.3` | console only, not needed before v2 |
| `dev.zio::zio-opentelemetry` | `3.1.18` | 4.0.0 is still RC12; stay on 3.1.x |
| `com.softwaremill.sttp.client4::core` | `4.0.26` | |
| `com.softwaremill.sttp.client4::zio` | `4.0.26` | ZIO backend |
| sttp-openai | resolve latest | wrapper over sttp client4 |
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
2. `core` imports nothing beyond `zio` and `zio-schema`. sttp must never appear
   in a public signature — it lives behind our own `LlmClient` trait.
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
| 3 | `Verify` / fail-closed, `Loop` / `onExhausted`, testkit + recorded fixtures |
| 4 | Postgres store, crash-recovery tests under testcontainers |
| 5 | Suspension and approval gates, resume API |
| 6 | One full example, docs site, publish `0.1.0` |

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

- **Blob-sized checkpoint values.** `Checkpoint.value` is stored inline with no
  size cap; a 40-page PDF extraction puts tens of megabytes in a Postgres row.
  The alternative spills past a threshold to blob storage. Deciding later is a
  store migration, so settle it before Week 4.
- **Node id collisions.** Ids are not path-qualified, so a sub-graph used twice,
  or two fan-out branches sharing a name, collide in the store and replay each
  other's output. Fix is to key on a path; it changes the store schema, so it
  pairs with the blob decision.
- **Fan-out failure mode.** `foreachPar` is fail-fast: one branch failing
  interrupts its siblings, discarding LLM calls already paid for and leaving a
  partially checkpointed fan-out. Safe on resume, not free. The alternative is
  to let all branches finish and collect failures. One call site either way.
- **Spend on a failed node is not recorded anywhere.** `Interpreter.effect`
  reads back a node's accumulated `Cost.ref` regardless of whether the body
  succeeded or failed, but only a successful node produces a `Checkpoint` to
  attach that spend to — there is no checkpoint for a node that failed. A
  malformed LLM reply that billed real tokens and then failed to parse leaves
  no trace. Same category as the other two: the honest fix is probably a
  lightweight "attempt record" distinct from a `Checkpoint`, which is a store
  schema decision. Decide before `store-postgres`.
- **`Spend` accumulation assumes single-currency pricing.** `Spend.+` sums
  `Option[Money]` via `Money.+`, which `require`s matching currencies and
  throws `IllegalArgumentException` — a bare `Throwable`, not a `GraphError` —
  on mismatch. That `require` fires inside `Cost.report`'s `ref.update`, so a
  currency conflict surfaces as a defect (`die`) straight through the
  `locally` block in `Interpreter.effect`, violating invariant #3. Dormant
  today because every `Model` prices in `"USD"`; live the moment a second
  provider prices in EUR or a caller builds a custom `Model` in another
  currency. A mixed-currency `Loop` or multi-provider budget will die rather
  than fail typed. Fix belongs with the budget interpreter (Week 2 continues
  there) — likely `Money` needs a fixed ledger currency or `Spend.+` needs to
  return a typed conflict instead of delegating to `Money.+` unguarded.
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
