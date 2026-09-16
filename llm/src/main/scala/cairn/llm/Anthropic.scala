package cairn.llm

import cairn.TokenCount
import zio.*
import zio.http.*
import zio.json.ast.Json

/**
 * [[LlmClient]] over zio-http. No sttp, no provider SDK.
 *
 * We use one endpoint and ignore the rest of the provider's surface, so a full client library would
 * be mostly dead weight. The cost is owning breakage when the API changes, which for a stable
 * endpoint is cheap.
 *
 * A schema is imposed by forcing a single tool call, because that is the mechanism the Messages API
 * offers for schema-constrained output.
 */
final class Anthropic(client: Client, apiKey: Config.Secret, baseUrl: String, apiVersion: String)
    extends LlmClient:
  import Anthropic.*

  def complete(request: LlmRequest): IO[LlmError, LlmResponse] =
    for
      url <- ZIO
        .fromEither(URL.decode(s"$baseUrl/v1/messages"))
        .mapError(e => LlmError.Rejected(s"bad base url: ${e.getMessage}"))
      resp <- post(url, encode(request))
      out <- decode(resp, request.schema.isDefined)
    yield out

  private def post(url: URL, body: Json): IO[LlmError, (Int, String)] =
    ZIO
      .scoped {
        client
          .batched(
            Request
              .post(url, Body.fromCharSequence(body.toString))
              .addHeader(Header.ContentType(MediaType.application.json))
              .addHeader("x-api-key", apiKey.stringValue)
              .addHeader("anthropic-version", apiVersion)
          )
          .flatMap(r => r.body.asString.map(r.status.code -> _))
      }
      .mapError(t => LlmError.Retryable(Option(t.getMessage).getOrElse(t.toString)))

  private def encode(r: LlmRequest): Json =
    val fields = Chunk(
      "model" -> Json.Str(r.model.id),
      "max_tokens" -> Json.Num(r.maxTokens),
      "messages" -> Json.Arr(
        Chunk(Json.Obj(Chunk("role" -> Json.Str("user"), "content" -> Json.Str(r.prompt))))
      )
    )

    val withSystem = r.system.fold(fields)(s => fields :+ ("system" -> Json.Str(s)))

    Json.Obj(r.schema.fold(withSystem) { schema =>
      withSystem ++ Chunk(
        "tools" -> Json.Arr(
          Chunk(
            Json.Obj(
              Chunk(
                "name" -> Json.Str(ToolName),
                "description" -> Json.Str("Return the result in this structure."),
                "input_schema" -> schema
              )
            )
          )
        ),
        "tool_choice" -> Json.Obj(Chunk("type" -> Json.Str("tool"), "name" -> Json.Str(ToolName)))
      )
    })

  private def decode(resp: (Int, String), structured: Boolean): IO[LlmError, LlmResponse] =
    val (status, body) = resp
    status match
      case 200 => parse(body, structured)
      case 401 | 403 => ZIO.fail(LlmError.Unauthorized)
      case 400 => ZIO.fail(LlmError.Rejected(body))
      case 429 => ZIO.fail(LlmError.Retryable("rate limited"))
      case s if s >= 500 => ZIO.fail(LlmError.Retryable(s"provider returned $s"))
      case s => ZIO.fail(LlmError.UnexpectedStatus(s, body))

  /**
   * With a forced tool call the payload is the tool_use block's `input`; without one it is a text
   * block. Both are handled so unstructured nodes work too.
   */
  private def parse(body: String, structured: Boolean): IO[LlmError, LlmResponse] =
    ZIO
      .fromEither(Json.decoder.decodeJson(body))
      .mapError(e => LlmError.Malformed(s"response was not JSON: $e", body))
      .flatMap { json =>
        val wanted = if structured then "tool_use" else "text"
        val payload = field(json, "content")
          .flatMap(_.asArray)
          .getOrElse(Chunk.empty)
          .collectFirst {
            case block if field(block, "type").flatMap(_.asString).contains(wanted) =>
              if structured then field(block, "input").map(_.toString)
              else field(block, "text").flatMap(_.asString)
          }
          .flatten

        val usage = field(json, "usage")
        val tokens = TokenCount(
          input = usage.flatMap(field(_, "input_tokens")).flatMap(num).getOrElse(0L),
          output = usage.flatMap(field(_, "output_tokens")).flatMap(num).getOrElse(0L)
        )
        val stopReason = field(json, "stop_reason").flatMap(_.asString)

        ZIO
          .fromOption(payload)
          .orElseFail(LlmError.Malformed(s"no $wanted block in response", body))
          .map(LlmResponse(_, tokens, stopReason))
      }

  private def field(j: Json, name: String): Option[Json] = j.asObject.flatMap(_.get(name))
  private def num(j: Json): Option[Long] = j.asNumber.map(_.value.longValue)

object Anthropic:
  private val ToolName = "cairn_structured_output"
  val DefaultBaseUrl = "https://api.anthropic.com"
  val DefaultApiVersion = "2023-06-01"

  def layer(
      baseUrl: String = DefaultBaseUrl,
      apiVersion: String = DefaultApiVersion
  ): ZLayer[Client, Config.Error, LlmClient] =
    ZLayer {
      for
        client <- ZIO.service[Client]
        key <- ZIO.config(Config.secret("anthropic_api_key"))
      yield new Anthropic(client, key, baseUrl, apiVersion)
    }
