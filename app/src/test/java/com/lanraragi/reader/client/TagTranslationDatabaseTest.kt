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
    }

    @Test
    fun `suggest skips namespace-name rows and expands loc`() {
        val hits = sample.suggest("a")
        val english = hits.map { it.second }
        assertEquals(false, english.any { it.startsWith("rows:") })
        assertEquals(true, english.contains("artist:asanagi"))
        assertEquals(true, english.contains("location:japan"))
    }
}
