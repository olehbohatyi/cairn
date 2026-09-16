package cairn.llm

import cairn.*
import zio.*
import zio.json.ast.Json
import zio.schema.Schema
import zio.schema.codec.JsonCodec

/**
 * A model and its price. Price lives with the model because the budget interpreter needs it at the
 * call site - a model whose cost we cannot compute cannot be budgeted. Cents per million tokens, so
 * the arithmetic stays in `Long`.
 */
final case class Model(id: String, inCents: Long, outCents: Long):
  def cost(t: TokenCount): Money =
    Money((t.input * inCents + t.output * outCents) / 1_000_000L, "USD")

object Model:
  // Check against current provider pricing before trusting a budget ceiling.
  val haiku = Model("claude-haiku-4-5", 100, 500)
  val sonnet = Model("claude-sonnet-5", 300, 1500)
  val opus = Model("claude-opus-5", 1500, 7500)

/**
 * One call, one reply. No conversation history: every cairn node is a fresh call by design, and the
 * fresh-context verifier depends on it.
 */
final case class LlmRequest(
    model: Model,
    system: Option[String],
    prompt: String,
    maxTokens: Int,
    schema: Option[Json]
)

final case class LlmResponse(text: String, tokens: TokenCount, stopReason: Option[String])

/**
 * Five cases, split by what a caller can *do* about them. `Retryable` covers rate limits, overload
 * and transport blips because the response to all three is the same; keeping them apart bought
 * nothing.
 */
enum LlmError:
  case Retryable(reason: String)
  case Rejected(reason: String)

  /**
   * The call succeeded and was billed, but the reply did not fit the schema. Distinct from
   * `Rejected` because retrying may well work.
   */
  case Malformed(reason: String, raw: String)
  case Unauthorized

  /**
   * A status code none of the above classifications fit. Kept structured rather than folded into
   * `Rejected`'s prose, so a caller can still branch on `status` without parsing a message.
   */
  case UnexpectedStatus(status: Int, body: String)

/** What cairn needs from a provider: one method. */
trait LlmClient:
  def complete(request: LlmRequest): IO[LlmError, LlmResponse]

/**
 * Builds [[Node.Effect]] values that call a model.
 *
 * The whole point of `structured`: one `Schema[A]` tells the provider what to emit, parses the
 * reply, and checkpoints the result. No DTO, no hand-written parser, and no way for checkpoint
 * format to drift from prompt format.
 */
final case class Llm(model: Model, maxTokens: Int = 4096, system: Option[String] = None):

  def withSystem(p: String): Llm = copy(system = Some(p))
  def withMaxTokens(n: Int): Llm = copy(maxTokens = n)

  /**
   * Output is `A` by parse, never by hope. A reply that does not fit fails the node - it never
   * yields a partially filled `A` and never falls through to a default.
   */
  def structured[I, A](id: String)(prompt: I => String)(using
      schema: Schema[A]
  ): Node.Effect[LlmClient, LlmError, I, A] =
    node(id, prompt, Some(JsonSchema.of[A]), schema) { (raw, _) =>
      JsonCodec
        .jsonDecoder(schema)
        .decodeJson(raw)
        .left
        .map(e => LlmError.Malformed(s"did not match schema: $e", raw))
    }

  /**
   * For output that genuinely is prose. Still checkpointed - `Schema[String]` is a schema like any
   * other.
   */
  def text[I](id: String)(prompt: I => String): Node.Effect[LlmClient, LlmError, I, String] =
    node(id, prompt, None, Schema.primitive[String])((raw, _) => Right(raw))

  private def node[I, A](
      id: String,
      prompt: I => String,
      schemaJson: Option[Json],
      schema: Schema[A]
  )(decode: (String, Schema[A]) => Either[LlmError, A]): Node.Effect[LlmClient, LlmError, I, A] =
    Node.Effect(
      NodeId(id),
      input =>
        ZIO
          .serviceWithZIO[LlmClient](
            _.complete(LlmRequest(model, system, prompt(input), maxTokens, schemaJson))
          )
          .flatMap { r =>
            Cost.report(Spend(Some(model.cost(r.tokens)), Some(r.tokens))) *>
              ZIO.fromEither(decode(r.text, schema)).mapError {
                // A truncated tool-call/reply produces the same generic parse
                // failure as any other malformed output. stop_reason is the
                // only signal that distinguishes "the model was cut off" from
                // "the model answered badly" - surface it in the message
                // rather than making the caller dig through the raw response.
                case LlmError.Malformed(reason, raw) if r.stopReason.contains("max_tokens") =>
                  LlmError.Malformed(s"response truncated at max_tokens; $reason", raw)
                case other => other
              }
          },
      schema
    )

object Llm:
  val haiku = Llm(Model.haiku)
  val sonnet = Llm(Model.sonnet)
  val opus = Llm(Model.opus)
