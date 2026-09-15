# cairn

Durable, typed orchestration for LLM workflows on ZIO.

> Your agent crashed at step four. It resumes at step four.

**Status: pre-release.** Nothing is published yet. This README describes the
target API and is written before the implementation, deliberately — if an
example here is awkward to write, the API is wrong. Expect breakage through 0.x.

## The problem

Four LLM calls in a `for` comprehension works on your laptop in an afternoon.
Then it reaches production:

- The pod restarts mid-run. Steps that already completed run again, cost money
  again, and send the customer a second email.
- A step waits on a human. Your process holds a thread open from Monday
  afternoon until Tuesday morning. Multiply by 400 concurrent runs.
- A prompt change makes the model chatty and one Friday costs €900 instead of €4.
- The model answers "Looks approved to me!" instead of a verdict, the parser
  doesn't match, and control falls through to the approval branch.
- None of it is testable, because every test hits a real API.

None of these are AI problems. They are plumbing problems, and every team
building on LLMs rediscovers all five.

## Quickstart

```scala
libraryDependencies ++= Seq(
  "io.github.olehbohatyi" %% "cairn-core"           % "0.1.0",
  "io.github.olehbohatyi" %% "cairn-llm"            % "0.1.0",
  "io.github.olehbohatyi" %% "cairn-store-postgres" % "0.1.0"
)
```

```scala
import cairn.*

case class ClaimData(policyId: String, amount: Money, cause: String) derives Schema
case class Decision(approved: Boolean, amount: Money, rationale: String) derives Schema

val claim =
  Graph[ClaimRequest, Decision]
    .node("extract")(Llm.haiku.structured[ClaimData])
    .fanOut("fraud", "estimate")(FraudCheck.run, Estimator.run)
    .join("decide")(Decision.combine)
    .verify("audit", freshContext = true)(Verdict.defaultFail)
    .approvalGate(when = _.amount > Money.eur(5000))
    .withBudget(Budget.eur(0.40))
    .withCheckpoints(Postgres.store)

claim.run(request)
// ZIO[R, GraphError[E], Either[Suspended, Decision]]
```

## What each line buys you

`.structured[ClaimData]` — the model's answer is parsed into your case class or
the node fails. The `Schema` derivation produces both the JSON Schema sent to
the model and the codec used to checkpoint the result. One declaration, no
second serialization story, no string matching.

`.fanOut` — branches run in parallel as ZIO fibers, each checkpointed
independently.

`.verify(freshContext = true)` — a second model reviews the decision without
seeing the reasoning that produced it. `Verdict.defaultFail` means anything
other than an explicit pass is a failure. There is no path to success that
bypasses a verifier.

`.approvalGate` — the run *stops*. No thread, no fiber, no memory held; state is
on disk. `Graph.resume(runId, Approval.Granted)` continues from that node when
the human decides, days later if necessary.

`.withBudget` — the run aborts at the ceiling. `BudgetExceeded` is a case in the
error channel, so the compiler makes you handle it.

`.withCheckpoints` — state is committed after every node. Crash at `estimate`
and a restart picks up there; `extract` and `fraud` are not re-run and not
re-billed.

## When not to use cairn

A single LLM call — summarize this, classify that. Use an HTTP client.

The line is roughly **more than two LLM calls that must happen in a defined
order, where a crash halfway through causes a real problem.** Below that, plain
ZIO is the right answer and we will tell you so.

## How it works

The graph is a value, not a function — a small ADT interpreted separately. That
buys four things a closure-based DSL cannot: validation of the whole graph at
startup, deterministic replay, a serializable structure for tooling, and
cross-cutting concerns (budget, tiering, tracing) as interpreters that wrap any
graph without node code knowing they exist.

**Checkpoints.** One row per `(runId, nodeId, attempt)` holding the node's
output encoded via its `Schema`, plus cost and token counts. Write-once, never
upsert: a collision means a concurrent worker took the same run, and the loser
abandons. "Replayed" means the committed value is read back — the node body does
not execute again.

**Determinism boundary.** Node bodies may be as non-deterministic as they like;
that is the point, they call models. The graph *structure* must be a function of
checkpointed state only. Branch on a committed node output, never on the clock
or a fresh UUID. The API makes this the path of least resistance.

**The crash window we do not close.** A node whose side effect landed but whose
checkpoint did not. No library can fix this — your email provider is not part of
our transaction. Every node receives an idempotency key derived from
`(runId, nodeId, attempt)`; pass it to Stripe, to SES, or into your own upsert.
Anyone claiming exactly-once across a third-party API is selling something.

## Testing

Swap one line and the whole workflow becomes an offline unit test:

```scala
claim.withCheckpoints(InMemory.store)
     .provide(Llm.recorded("fixtures/claim-happy-path.json"))
```

`cairn-testkit` ships a recording client that captures request/response pairs on
first run and replays them afterwards. The suite runs in milliseconds, costs
nothing, and is deterministic. This is not a testing afterthought — it was built
in week three for exactly the reason you are thinking.

## Persistence backends

| Module | Backend | Use for |
|---|---|---|
| `cairn-store-memory` | none | tests, single-process experiments |
| `cairn-store-postgres` | Postgres over JDBC | the default |
| `cairn-store-dbos` | [DBOS](https://github.com/dbos-inc) via `dbos4s` | teams already running DBOS |

`CheckpointStore` is a four-method trait. Writing one for your own store is an
afternoon.

## Relationship to other tools

cairn is not a durable execution engine and does not try to be one. Temporal,
Restate and DBOS solve persistence and replay, and `cairn-store-dbos` delegates
to DBOS rather than competing with it. cairn is the layer above: typed agent
orchestration, budgets, model tiering, fail-closed verification, approval gates.

The closest analogue is LangGraph, which has no JVM equivalent. The difference
is the type system — `ZIO[R, E, A]` makes a node's dependencies and failure
modes compile-time facts rather than runtime surprises.

## Requirements

Scala 3.9.0 (LTS), ZIO 2.1.26, JDK 17+. No Scala 2.13 build.

Artifacts built on Scala 3.9 cannot be consumed by Scala 3.3 projects. If you
are pinned to 3.3 LTS, cairn will not work for you yet — open an issue and say
so, it is the kind of signal that changes the decision.

## Roadmap

| | |
|---|---|
| 0.1 | core graph, LLM nodes, budget, tiering, verification, loops, Postgres store, approval gates |
| 0.2 | streaming input (Kafka), `keyBy` deduplication |
| 0.3 | replay console — inspect a run, re-execute from any node against new code |
| later | MCP server, gRPC nodes, binary-compatibility guarantees |

## Contributing

Issues and discussion welcome. The library's own CI runs a cairn graph over
incoming pull requests: four parallel reviewers into an aggregator, then an
asymmetric verifier with fresh context and a fail-closed verdict. It is the
best demonstration we have, and it occasionally disagrees with the maintainer.

## License

Apache 2.0
