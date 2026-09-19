package cairn.store.postgres

import zio.schema.{DeriveSchema, Schema}
import zio.test.*

/**
 * The store persists `DynamicValue` as JSON and replay rebuilds the node's own type from it via
 * `Schema#fromDynamic`. The property that matters is therefore the full round trip
 * `A -> DynamicValue -> JSON -> DynamicValue -> A`, not just that JSON comes out. Offline: no
 * Docker, no network (CLAUDE.md invariant #4).
 */
object DynamicValueJsonSpec extends ZIOSpecDefault:

  final case class Address(street: String, zip: Option[String])
  final case class Claim(
      id: Long,
      amount: Double,
      approved: Boolean,
      tags: List[String],
      address: Address,
      note: Option[String]
  )
  enum Verdict:
    case Pass
    case Fail(reason: String)

  final case class Line(sku: String, qty: Int, note: Option[String])
  final case class Order(
      id: Long,
      lines: List[Line],
      shipTo: Option[Address],
      history: List[Verdict]
  )
  enum Event:
    case Created(order: Order)
    case Reviewed(by: String, verdicts: List[Verdict])
    case Batch(events: List[Order])

  private given Schema[Address] = DeriveSchema.gen[Address]
  private given Schema[Claim] = DeriveSchema.gen[Claim]
  private given Schema[Verdict] = DeriveSchema.gen[Verdict]
  private given Schema[Line] = DeriveSchema.gen[Line]
  private given Schema[Order] = DeriveSchema.gen[Order]
  private given Schema[Event] = DeriveSchema.gen[Event]

  private val order1 = Order(
    1L,
    List(Line("a", 2, Some("gift")), Line("b", 1, None)),
    Some(Address("Main St", Some("12345"))),
    List(Verdict.Pass, Verdict.Fail("late"))
  )
  private val order2 = Order(2L, Nil, None, Nil)

  private def roundTrip[A](value: A)(using schema: Schema[A]): Either[String, A] =
    for
      decoded <- DynamicValueJson.decode(DynamicValueJson.encode(schema.toDynamic(value)))
      back <- schema.fromDynamic(decoded)
    yield back

  private def check[A: Schema](name: String, value: A) =
    test(name)(assertTrue(roundTrip(value) == Right(value)))

  def spec = suite("DynamicValue JSON round trip")(
    check("Int", 42),
    check("negative Int", -7),
    check("Long beyond Int range", 9_000_000_000L),
    check("Boolean", true),
    check("String", "hello"),
    check("String with quotes, newline and unicode", "a \"quoted\"\nline é 中"),
    check("Double", 1.5d),
    check("BigDecimal", BigDecimal("12345678901234567890.123456789")),
    check("empty String", ""),
    check("Option Some", Option(3)),
    check("Option None", Option.empty[Int]),
    check("List", List(1, 2, 3)),
    check("empty List", List.empty[Int]),
    check("nested record", Claim(1L, 250.5, true, List("a", "b"), Address("Main St", None), None)),
    check(
      "record with every optional present",
      Claim(2L, 0.0, false, Nil, Address("Side St", Some("12345")), Some("note"))
    ),
    check("sum type, case object", Verdict.Pass),
    check("sum type, case with data", Verdict.Fail("no evidence")),
    check("record holding a list of records, an optional record and a list of sum cases", order1),
    check("sum case holding a deep record", Event.Created(order1)),
    check(
      "sum case holding a list of sum cases",
      Event.Reviewed("ann", List(Verdict.Pass, Verdict.Fail("x")))
    ),
    check("sum case holding a list of deep records", Event.Batch(List(order1, order2))),
    check("Map with record values", Map("a" -> Line("a", 1, None), "b" -> Line("b", 2, Some("n")))),
    check("Either Left", Left("err"): Either[String, Int]),
    check("Either Right", Right(5): Either[String, Int]),
    check("tuple", (1, "one")),
    test("a NUL character is always escaped in the JSON, never emitted raw, and round trips") {
      val nul = 0.toChar
      val value = s"a${nul}b"
      val json = DynamicValueJson.encode(Schema.primitive[String].toDynamic(value))
      assertTrue(
        !json.contains(nul),
        json.contains("\\u0000"),
        roundTrip(value) == Right(value)
      )
    },
    test("garbage input is a Left, not an exception") {
      assertTrue(DynamicValueJson.decode("{not json").isLeft)
    },
    test("a well-formed JSON value that is not a DynamicValue is a Left") {
      assertTrue(DynamicValueJson.decode("\"just a string\"").isLeft)
    }
  )
