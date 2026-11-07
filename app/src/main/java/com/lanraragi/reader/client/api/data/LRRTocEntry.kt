package com.lanraragi.reader.client.api.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder

/**
 * One chapter of an archive's table of contents (`toc`, LANraragi 0.9.70+).
 * [page] is the server's 1-BASED page and is not range-checked upstream;
 * reading.ChapterIndex validates it against the real page count.
 */
@Serializable
data class LRRTocEntry(
    val page: Int,
    @Serializable(with = FlexibleStringSerializer::class) val name: String = "",
)

/**
 * Reads `toc`, turning any unexpected shape into an empty list. The same
 * `/metadata` response drives the reader's progress decision, so a surprise
 * in this optional hint must never fail the whole archive decode.
 */
object LenientTocSerializer : KSerializer<List<LRRTocEntry>> {
    private val delegate = ListSerializer(LRRTocEntry.serializer())

    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor("LenientToc", delegate.descriptor)

    override fun deserialize(decoder: Decoder): List<LRRTocEntry> {
        if (decoder !is JsonDecoder) return delegate.deserialize(decoder)
        val element = decoder.decodeJsonElement()
        return try {
            decoder.json.decodeFromJsonElement(delegate, element)
        } catch (ignored: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException.
            emptyList()
        }
    }

    override fun serialize(encoder: Encoder, value: List<LRRTocEntry>) =
        delegate.serialize(encoder, value)
}
