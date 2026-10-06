package com.lanraragi.reader.client

import android.util.Base64
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contract for the namespace-aware lookups on [TagTranslationDatabase].
 *
 * The dataset keys are EhViewer-style (`a:`, `p:`, `o:`, …, plus `n:<ns>`
 * rows for namespace names). LANraragi archives carry full namespaces and
 * some LRR-only spellings (`series` for parody, bare `misc` tags, mixed
 * case from user edits), so the database has to map those onto the dataset
 * before the byte-wise binary search can hit anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class TagTranslationDatabaseTest {

    private fun db(vararg entries: Pair<String, String>): TagTranslationDatabase {
        val body = entries
            .sortedBy { it.first }
            .joinToString("") { (key, value) ->
                key + "\r" + Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP) + "\n"
            }
            .toByteArray()
        val buffer = Buffer().writeInt(body.size).write(body)
        return TagTranslationDatabase("test", buffer)
    }

    private val sample = db(
        "a:asanagi" to "朝凪",
        "p:touhou project" to "东方Project",
        "o:full color" to "全彩",
        "g:asanagi" to "朝凪社",
        "loc:japan" to "日本",
        "r:manga" to "漫画",
        "n:artist" to "艺术家",
        "n:parody" to "原作",
        "n:other" to "其他",
        "n:location" to "地点",
    )

    @Test
    fun `getTranslation still answers exact dataset keys`() {
        assertEquals("朝凪", sample.getTranslation("a:asanagi"))
        assertNull(sample.getTranslation("asanagi"))
    }

    @Test
    fun `translateTag maps LANraragi namespaces onto dataset prefixes`() {
        assertEquals("朝凪", sample.translateTag("artist", "asanagi"))
        assertEquals("东方Project", sample.translateTag("parody", "touhou project"))
        assertEquals("东方Project", sample.translateTag("series", "touhou project"))
        assertEquals("日本", sample.translateTag("location", "japan"))
        assertEquals("漫画", sample.translateTag("category", "manga"))
        assertEquals("全彩", sample.translateTag("other", "full color"))
    }

    @Test
    fun `translateTag accepts EhViewer single-letter shorthand namespaces`() {
        assertEquals("朝凪", sample.translateTag("a", "asanagi"))
        assertEquals("东方Project", sample.translateTag("p", "touhou project"))
    }

    @Test
    fun `translateTag ignores case and surrounding whitespace on the value`() {
        assertEquals("朝凪", sample.translateTag("Artist", " Asanagi "))
    }

    @Test
    fun `translateTag probes the dataset for namespace-less tags`() {
        assertEquals("全彩", sample.translateTag("misc", "full color"))
        assertEquals("全彩", sample.translateTag(null, "full color"))
        assertEquals("全彩", sample.translateTag("", "full color"))
        // Artist wins over group when a bare tag is ambiguous.
        assertEquals("朝凪", sample.translateTag("misc", "asanagi"))
    }

    @Test
    fun `translateTag returns null for unknown namespaces and misses`() {
        assertNull(sample.translateTag("date_added", "asanagi"))
        assertNull(sample.translateTag("source", "asanagi"))
        assertNull(sample.translateTag("artist", "nobody"))
        assertNull(sample.translateTag("misc", "nobody"))
    }

    @Test
    fun `translateNamespace maps aliases onto the n rows`() {
        assertEquals("艺术家", sample.translateNamespace("artist"))
        assertEquals("艺术家", sample.translateNamespace("a"))
        assertEquals("原作", sample.translateNamespace("parody"))
        assertEquals("原作", sample.translateNamespace("series"))
        assertEquals("其他", sample.translateNamespace("misc"))
        assertEquals("其他", sample.translateNamespace("Other"))
        assertEquals("地点", sample.translateNamespace("location"))
        assertNull(sample.translateNamespace("date_added"))
        assertNull(sample.translateNamespace("group"))
        // Values under `category:` translate as reclass, but the header must
        // not be relabelled "reclass".
        assertNull(sample.translateNamespace("category"))
    }

    @Test
    fun `suggest skips namespace-name rows and expands loc`() {
        val hits = sample.suggest("a")
        val english = hits.map { it.second }
        assertEquals(false, english.any { it.startsWith("rows:") })
        assertEquals(true, english.contains("artist:asanagi"))
        assertEquals(true, english.contains("location:japan"))
    }

    // Audit C26 / SEC-11: the length prefix of the third-party file is untrusted.
    @Test(expected = java.io.IOException::class)
    fun `negative length prefix is rejected as a corrupt dataset`() {
        TagTranslationDatabase("bad", Buffer().writeInt(-1))
    }

    @Test
    fun `oversized length prefix is rejected before allocating`() {
        val e = runCatching {
            TagTranslationDatabase("bad", Buffer().writeInt(TagTranslationDatabase.MAX_DATASET_BYTES + 1))
        }.exceptionOrNull()
        // Not an EOFException from reading a body that was never there.
        assertEquals(true, e?.message?.contains("out of range"))
    }

    // Audit SEC-01: the body comes from a third-party repo and is only
    // checked against its own SHA-1, so its row structure is untrusted.

    private fun row(key: String, value: String) =
        key + "\r" + Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP) + "\n"

    private val validBody = (row("a:asanagi", "朝凪") + row("o:full color", "全彩") + row("p:touhou", "东方"))

    /** Bodies that break the `key\rvalue\n` row contract, by failure kind. */
    private val malformedBodies: Map<String, ByteArray> = mapOf(
        "empty" to ByteArray(0),
        "row without CR" to (row("a:asanagi", "朝凪") + "o:full color\n" + row("p:touhou", "东方")).toByteArray(),
        "only row without CR" to "a:asanagi\n".toByteArray(),
        "no trailing LF" to validBody.dropLast(1).toByteArray(),
        "truncated mid-value" to validBody.toByteArray().copyOf(validBody.toByteArray().size - 20),
        "truncated mid-key" to (row("a:asanagi", "朝凪") + "o:full").toByteArray(),
        "truncated after CR" to (row("a:asanagi", "朝凪") + "o:full color\r").toByteArray(),
        "empty key" to (row("a:asanagi", "朝凪") + row("", "x")).toByteArray(),
        "empty value" to (row("a:asanagi", "朝凪") + "o:full color\r\n").toByteArray(),
        "two CRs" to (row("a:asanagi", "朝凪") + "o:x\ry\rz\n").toByteArray(),
        "blank line" to (row("a:asanagi", "朝凪") + "\n" + row("p:touhou", "东方")).toByteArray(),
        "single byte" to byteArrayOf(0x0A),
    )

    /** Keys that steer the binary search below, onto, and past every row. */
    private val probes = listOf("", "0", "a:asanagi", "a:zzz", "o:full color", "o:full", "p:touhou", "zzzz", "ÿ")

    @Test
    fun `search on a malformed body returns null instead of throwing`() {
        for ((kind, body) in malformedBodies) {
            for (probe in probes) {
                val result = runCatching { TagTranslationDatabase.search(body, probe.toByteArray()) }
                assertNull("$kind / '$probe' threw ${result.exceptionOrNull()}", result.exceptionOrNull())
            }
        }
        // Rows the search cannot delimit never translate.
        val noCr = malformedBodies.getValue("only row without CR")
        assertNull(TagTranslationDatabase.search(noCr, "a:asanagi".toByteArray()))
        val noLf = "a:asanagi\r5pyd5Yeq".toByteArray()
        assertNull(TagTranslationDatabase.search(noLf, "a:asanagi".toByteArray()))
        val truncated = malformedBodies.getValue("truncated after CR")
        assertNull(TagTranslationDatabase.search(truncated, "o:full color".toByteArray()))
        assertNull(TagTranslationDatabase.search(ByteArray(0), "a:asanagi".toByteArray()))
    }

    @Test
    fun `search returns null for a row whose value is not base64`() {
        // A lone base64 character cannot be decoded (Base64 throws).
        val body = "a:asanagi\rA\n".toByteArray()
        assertNull(TagTranslationDatabase.search(body, "a:asanagi".toByteArray()))
    }

    @Test
    fun `search on a well-formed body finds every row and misses cleanly`() {
        val body = validBody.toByteArray()
        assertEquals("朝凪", TagTranslationDatabase.search(body, "a:asanagi".toByteArray()))
        assertEquals("全彩", TagTranslationDatabase.search(body, "o:full color".toByteArray()))
        assertEquals("东方", TagTranslationDatabase.search(body, "p:touhou".toByteArray()))
        for (miss in listOf("", "0", "a:asanag", "a:asanagii", "o:full", "zzzz")) {
            assertNull(miss, TagTranslationDatabase.search(body, miss.toByteArray()))
        }
    }

    @Test
    fun `malformed bodies are rejected when loading`() {
        for ((kind, body) in malformedBodies) {
            assertEquals(kind, false, TagTranslationDatabase.isWellFormed(body))
            val e = runCatching {
                TagTranslationDatabase("bad", Buffer().writeInt(body.size).write(body))
            }.exceptionOrNull()
            assertEquals("$kind: $e", true, e is java.io.IOException)
        }
        assertEquals(true, TagTranslationDatabase.isWellFormed(validBody.toByteArray()))
    }

    @Test
    fun `a body shorter than its length prefix is rejected`() {
        val body = validBody.toByteArray()
        val e = runCatching {
            TagTranslationDatabase("bad", Buffer().writeInt(body.size + 10).write(body))
        }.exceptionOrNull()
        assertEquals("$e", true, e is java.io.IOException)
    }

    @Test
    fun `load returns null for a malformed file and a database for a valid one`() {
        val dir = java.nio.file.Files.createTempDirectory("tagdb").toFile()
        try {
            val bad = java.io.File(dir, "bad")
            val badBody = malformedBodies.getValue("no trailing LF")
            bad.writeBytes(Buffer().writeInt(badBody.size).write(badBody).readByteArray())
            assertNull(TagTranslationDatabase.load("bad", bad))

            val good = java.io.File(dir, "good")
            val goodBody = validBody.toByteArray()
            good.writeBytes(Buffer().writeInt(goodBody.size).write(goodBody).readByteArray())
            assertEquals("朝凪", TagTranslationDatabase.load("good", good)?.translateTag("artist", "asanagi"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
