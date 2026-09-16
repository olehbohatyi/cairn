package cairn.llm

import cairn.*
import cairn.store.InMemoryStore
import zio.*
import zio.schema.{DeriveSchema, Schema}
import zio.test.*

/**
 * A structured node end to end, against a stub client. No network.
 *
 * The last test is the one that matters: a model reply that does not fit the schema must fail the
 * node, not produce a half-filled value. That is the "Looks approved to me!" failure the library
 * exists to prevent.
 */
object StructuredNodeSpec extends ZIOSpecDefault:

  final case class Triage(severity: String, confidence: Double)
  object Triage:
    given Schema[Triage] = DeriveSchema.gen[Triage]

  /**
   * Counts calls so replay is observable, same trick as ReplaySpec. `stopReason` defaults to `None` -
   * only the truncation test below cares.
   */
  private def stub(reply: String, calls: Ref[Int], stopReason: Option[String] = None): LlmClient =
    new LlmClient:
      def complete(request: LlmRequest): IO[LlmError, LlmResponse] =
        calls.update(_ + 1).as(LlmResponse(reply, TokenCount(10, 20), stopReason))

  private val node =
    Llm.haiku.structured[String, Triage]("triage")(alert => s"Classify: $alert")

  def spec = suite("structured node")(
    test("parses a well-formed reply into the case class") {
      for
        calls <- Ref.make(0)
        store <- InMemoryStore.make
        out <- Interpreter
          .run(node, "disk full", RunId("r1"))
          .provideEnvironment(
            ZEnvironment(stub("""{"severity":"low","confidence":0.94}""", calls))
              .add(store)
          )
      yield assertTrue(out == Triage("low", 0.94))
    },
    test("a checkpointed node does not call the provider again") {
      for
        calls <- Ref.make(0)
        store <- InMemoryStore.make
        env = ZEnvironment(stub("""{"severity":"low","confidence":0.94}""", calls)).add(store)
        _ <- Interpreter.run(node, "disk full", RunId("r2")).provideEnvironment(env)
        _ <- Interpreter.run(node, "disk full", RunId("r2")).provideEnvironment(env)
        count <- calls.get
      yield assertTrue(count == 1)
    },
    test("prose instead of JSON fails the node rather than yielding a value") {
      for
        calls <- Ref.make(0)
        store <- InMemoryStore.make
        result <- Interpreter
          .run(node, "disk full", RunId("r3"))
          .provideEnvironment(
            ZEnvironment(stub("Looks approved to me!", calls)).add(store)
          )
          .either
      yield assertTrue(result.isLeft)
    },
    test("valid JSON of the wrong shape also fails") {
      for
        calls <- Ref.make(0)
        store <- InMemoryStore.make
        result <- Interpreter
          .run(node, "disk full", RunId("r4"))
          .provideEnvironment(
            ZEnvironment(stub("""{"severity":"low"}""", calls)).add(store)
          )
          .either
      yield assertTrue(result.isLeft)
    },
    test("a checkpointed node's cost and tokens are recorded") {
      for
        calls <- Ref.make(0)
        store <- InMemoryStore.make
        env = ZEnvironment(stub("""{"severity":"low","confidence":0.94}""", calls)).add(store)
        runId = RunId("r5")
        _ <- Interpreter.run(node, "disk full", runId).provideEnvironment(env)
        committed <- store.get(runId, NodeId("triage"), Attempt.first)
      yield assertTrue(
        committed.flatMap(_.cost) == Some(Model.haiku.cost(TokenCount(10, 20))),
        committed.flatMap(_.tokens) == Some(TokenCount(10, 20))
      )
    },
    test("a max_tokens truncation is distinguishable from ordinary malformed output") {
      for
        calls <- Ref.make(0)
        store <- InMemoryStore.make
        result <- Interpreter
          .run(node, "disk full", RunId("r6"))
          .provideEnvironment(
            ZEnvironment(stub("""{"severity":"lo""", calls, Some("max_tokens"))).add(store)
          )
          .either
      yield assertTrue(result match
        case Left(GraphError.NodeFailed(_, LlmError.Malformed(reason, _))) =>
          reason.contains("max_tokens")
        case _ => false)
    }
  )
