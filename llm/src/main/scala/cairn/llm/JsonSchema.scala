package cairn.llm

import zio.Chunk
import zio.json.ast.Json
import zio.schema.{Schema, StandardType}

/**
 * JSON Schema from a zio-schema `Schema[A]` - the payoff for putting `Schema[O]` on `Node.Effect`.
 * The same declaration that checkpoints a value tells the provider what shape to emit.
 *
 * Handled: records, primitives, options, sequences, string-keyed maps, and enums of case objects.
 * Anything else yields a permissive `{}` - a loose schema still produces usable output, where a
 * hard failure would block a node that would otherwise work.
 *
 * TODO: sum types with data-carrying cases need `oneOf` plus a discriminator, and providers
 * disagree on what they accept. Revisit when someone models a real domain; permissive will start
 * hiding bugs.
 */
object JsonSchema:

  def of[A](using schema: Schema[A]): Json = derive(schema)

  private def derive(schema: Schema[?]): Json =
    schema match
      case s: Schema.Lazy[?] => derive(s.schema)
      case s: Schema.Transform[?, ?, ?] => derive(s.schema) // codec change, not shape change
      case s: Schema.Optional[?] => derive(s.schema) // optionality lives in `required`
      case s: Schema.Primitive[?] => primitive(s.standardType)
      case s: Schema.Sequence[?, ?, ?] =>
        obj("type" -> str("array"), "items" -> derive(s.elementSchema))
      case s: Schema.Map[?, ?] =>
        obj("type" -> str("object"), "additionalProperties" -> derive(s.valueSchema))
      case s: Schema.Record[?] => record(s)
      case s: Schema.Enum[?] => enumeration(s)
      case _ => Json.Obj()

  private def record(r: Schema.Record[?]): Json =
    obj(
      "type" -> str("object"),
      "properties" -> Json.Obj(
        Chunk.fromIterable(r.fields.map(f => f.name.toString -> describe(derive(f.schema), f)))
      ),
      // The one place optionality is observable, which is why Optional above
      // can simply unwrap.
      "required" -> arr(r.fields.filterNot(f => optional(f.schema)).map(f => str(f.name.toString))),
      // Required by providers enforcing strict structured output.
      "additionalProperties" -> Json.Bool(false)
    )

  private def enumeration(e: Schema.Enum[?]): Json =
    if e.cases.forall(c => caseObject(c.schema)) then
      obj("type" -> str("string"), "enum" -> arr(e.cases.map(c => str(c.id.toString))))
    else Json.Obj()

  private def primitive(st: StandardType[?]): Json =
    st match
      case StandardType.StringType => obj("type" -> str("string"))
      case StandardType.BoolType => obj("type" -> str("boolean"))
      case StandardType.IntType | StandardType.LongType | StandardType.ShortType =>
        obj("type" -> str("integer"))
      case StandardType.DoubleType | StandardType.FloatType | StandardType.BigDecimalType =>
        obj("type" -> str("number"))
      case StandardType.UUIDType => obj("type" -> str("string"), "format" -> str("uuid"))
      case StandardType.InstantType => obj("type" -> str("string"), "format" -> str("date-time"))
      case StandardType.LocalDateType => obj("type" -> str("string"), "format" -> str("date"))
      case _ => obj("type" -> str("string"))

  /**
   * zio-schema `@description` annotations pass through as field descriptions - the documentation
   * the developer already wrote becomes instruction to the model, for free.
   */
  private def describe(j: Json, f: Schema.Field[?, ?]): Json =
    val doc = f.annotations.collectFirst { case d: zio.schema.annotation.description => d.text }
    (j, doc) match
      case (Json.Obj(fields), Some(text)) => Json.Obj(fields :+ ("description" -> str(text)))
      case _ => j

  private def optional(s: Schema[?]): Boolean = s match
    case _: Schema.Optional[?] => true
    case l: Schema.Lazy[?] => optional(l.schema)
    case _ => false

  private def caseObject(s: Schema[?]): Boolean = s match
    case r: Schema.Record[?] => r.fields.isEmpty
    case l: Schema.Lazy[?] => caseObject(l.schema)
    case t: Schema.Transform[?, ?, ?] => caseObject(t.schema)
    case _ => false

  private def str(s: String): Json = Json.Str(s)
  private def obj(fs: (String, Json)*): Json = Json.Obj(Chunk.fromIterable(fs))
  private def arr(js: Iterable[Json]): Json = Json.Arr(Chunk.fromIterable(js))
