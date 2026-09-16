package cairn.llm

import zio.json.ast.Json
import zio.schema.{DeriveSchema, Schema}
import zio.test.*

/**
 * Schema derivation is pure, so it is fully testable with no provider and no network - CLAUDE.md
 * invariant #4. These tests are the guard against the failure mode that matters most here: a schema
 * we send that does not match the decoder we parse with, which shows up in production as
 * `MalformedOutput` on valid model replies.
 */
object JsonSchemaSpec extends ZIOSpecDefault:

  final case class ClaimData(policyId: String, amountCents: Long, cause: Option[String])
  object ClaimData:
    given Schema[ClaimData] = DeriveSchema.gen[ClaimData]

  private def at(j: Json, path: String*): Option[Json] =
    path.foldLeft(Option(j))((acc, k) => acc.flatMap(_.asObject).flatMap(_.get(k)))

  def spec = suite("JsonSchema")(
    test("a record becomes an object schema with typed properties") {
      val js = JsonSchema.of[ClaimData]
      assertTrue(
        at(js, "type").flatMap(_.asString).contains("object"),
        at(js, "properties", "policyId", "type").flatMap(_.asString).contains("string"),
        at(js, "properties", "amountCents", "type").flatMap(_.asString).contains("integer")
      )
    },
    test("Option fields are absent from required, non-Option fields are present") {
      val js = JsonSchema.of[ClaimData]
      val required = at(js, "required")
        .flatMap(_.asArray)
        .map(_.flatMap(_.asString.toList))
        .getOrElse(Nil)

      assertTrue(
        required.contains("policyId"),
        required.contains("amountCents"),
        !required.contains("cause")
      )
    },
    test("additionalProperties is false, as strict structured output requires") {
      val js = JsonSchema.of[ClaimData]
      assertTrue(at(js, "additionalProperties").flatMap(_.asBoolean).contains(false))
    },
    test("an Option field still carries its underlying type") {
      val js = JsonSchema.of[ClaimData]
      assertTrue(at(js, "properties", "cause", "type").flatMap(_.asString).contains("string"))
    }
  )
