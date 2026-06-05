package com.lanraragi.reader.client.api.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A serializer that accepts a JSON boolean, integer, or string and converts it
 * to a Boolean. LANraragi's `/api/info` returns flags like `has_password` as
 * real JSON booleans on modern servers but as integers (0/1) on older ones —
 * the OpenAPI ServerInfo schema documents both. A plain Boolean field throws on
 * the integer form, which fails the connect flow against older servers.
 *
 * Truthiness: JSON `true` / `"true"` → true; any non-zero integer (or its
 * string form) → true; everything else (including `0`, `null`, unparseable)
 * → false.
 *
 * @see FlexibleStringSerializer — the same idea for fields that may arrive as a
 *   boolean/number/string but are modelled as a String (e.g. `isnew`, `pinned`).
 */
object FlexibleBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        if (decoder !is JsonDecoder) return decoder.decodeBoolean()
        val prim = decoder.decodeJsonElement().jsonPrimitive
        prim.booleanOrNull?.let { return it }
        return (prim.intOrNull ?: 0) != 0
    }

    override fun serialize(encoder: Encoder, value: Boolean) {
        encoder.encodeBoolean(value)
    }
}
