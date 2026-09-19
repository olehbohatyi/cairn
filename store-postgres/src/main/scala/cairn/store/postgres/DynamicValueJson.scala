package cairn.store.postgres

import zio.schema.codec.JsonCodec
import zio.schema.{DynamicValue, Schema}

/**
 * `core` holds checkpoint values as `DynamicValue` and deliberately has no codec (invariant #2);
 * this is where they become JSON for the `value` column. The store never sees the node's own
 * `Schema[O]`, so the encoding has to be lossless for `DynamicValue` itself, not merely for the
 * value it wraps - `DynamicValueJsonSpec` checks that.
 */
private[postgres] object DynamicValueJson:
  private val schema: Schema[DynamicValue] = DynamicValue.schema

  def encode(value: DynamicValue): String =
    JsonCodec.jsonEncoder(schema).encodeJson(value).toString

  def decode(json: String): Either[String, DynamicValue] =
    JsonCodec.jsonDecoder(schema).decodeJson(json)
