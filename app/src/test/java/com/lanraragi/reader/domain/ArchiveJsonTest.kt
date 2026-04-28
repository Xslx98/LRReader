package com.lanraragi.reader.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for [Archive]'s `@Serializable` shape. The L1 audit
 * item plans to store an entire Archive as a single JSON column to
 * remove the need for a schema bump every time a new display field is
 * added; this suite locks in the wire format so a future change that
 * silently breaks the round trip surfaces in CI.
 */
class ArchiveJsonTest {

    private val json = Json {
        // Forgive future producers/consumers that ship extra fields.
        ignoreUnknownKeys = true
        // Keep nulls out of the JSON for compactness; null is the default
        // for `summary` and the parser fills it back on decode.
        encodeDefaults = false
    }

    private fun sampleArchive(
        arcid: String = "deadbeef",
        title: String = "Sample",
        tags: Map<String, List<String>> = mapOf(
            "artist" to listOf("alice", "bob"),
            "language" to listOf("english"),
            "misc" to listOf("standalone"),
        ),
        pagecount: Int = 24,
        progress: Int = 6,
        extension: String = "zip",
        filename: String = "sample.zip",
        thumbnailUrl: String = "https://example.test/api/archives/deadbeef/thumbnail",
        rating: Float = 4.5f,
        isnew: Boolean = false,
        lastreadtime: Long = 1_700_000_000L,
        summary: String? = "a quick brown fox",
        serverProfileId: Long = 7L,
    ) = Archive(
        arcid = arcid,
        title = title,
        tags = tags,
        pagecount = pagecount,
        progress = progress,
        extension = extension,
        filename = filename,
        thumbnailUrl = thumbnailUrl,
        rating = rating,
        isnew = isnew,
        lastreadtime = lastreadtime,
        summary = summary,
        serverProfileId = serverProfileId,
    )

    @Test
    fun `round-trip preserves every field`() {
        val original = sampleArchive()
        val encoded = json.encodeToString(Archive.serializer(), original)
        val decoded = json.decodeFromString(Archive.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `round-trip preserves null summary`() {
        val original = sampleArchive(summary = null)
        val encoded = json.encodeToString(Archive.serializer(), original)
        val decoded = json.decodeFromString(Archive.serializer(), encoded)

        assertNull(decoded.summary)
    }

    @Test
    fun `round-trip preserves nested tag map order`() {
        val original = sampleArchive(
            tags = linkedMapOf(
                "artist" to listOf("z", "a"),
                "language" to listOf("english"),
            )
        )
        val encoded = json.encodeToString(Archive.serializer(), original)
        val decoded = json.decodeFromString(Archive.serializer(), encoded)

        assertEquals(listOf("artist", "language"), decoded.tags.keys.toList())
        assertEquals(listOf("z", "a"), decoded.tags["artist"])
    }

    @Test
    fun `decode tolerates an unknown extra field`() {
        // Simulate a future producer that adds a field this build does
        // not know about — must not crash.
        val futureJson = """
            {
              "arcid":"x",
              "title":"y",
              "tags":{},
              "pagecount":0,
              "progress":0,
              "extension":"",
              "filename":"",
              "thumbnailUrl":"",
              "rating":0.0,
              "isnew":false,
              "lastreadtime":0,
              "serverProfileId":0,
              "futureColumn":"hi"
            }
        """.trimIndent()
        val decoded = json.decodeFromString(Archive.serializer(), futureJson)

        assertEquals("x", decoded.arcid)
        assertEquals("y", decoded.title)
        assertTrue(decoded.tags.isEmpty())
    }

    @Test
    fun `decode populates default summary when omitted`() {
        // Producer encoded with encodeDefaults=false so summary is absent.
        val original = sampleArchive(summary = null)
        val encoded = json.encodeToString(Archive.serializer(), original)
        // summary should not be in the JSON at all
        assertTrue("summary should be omitted", !encoded.contains("\"summary\""))

        val decoded = json.decodeFromString(Archive.serializer(), encoded)
        assertNull(decoded.summary)
    }
}
