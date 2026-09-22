package com.lanraragi.reader.util

import android.util.Base64
import com.lanraragi.reader.client.TagTranslationDatabase
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [TagTranslationUtil] formats a `namespace:value` tag for display. It must
 * route through the database's namespace-aware lookup so LANraragi spellings
 * (`series`, bare `misc` tags, mixed case) translate, and fall back to the
 * original text piecewise when only one half is known.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class TagTranslationUtilTest {

    private fun db(vararg entries: Pair<String, String>): TagTranslationDatabase {
        val body = entries
            .sortedBy { it.first }
            .joinToString("") { (key, value) ->
                key + "\r" + Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP) + "\n"
            }
            .toByteArray()
        return TagTranslationDatabase("test", Buffer().writeInt(body.size).write(body))
    }

    private val sample = db(
        "a:asanagi" to "朝凪",
        "p:touhou project" to "东方Project",
        "o:full color" to "全彩",
        "n:artist" to "艺术家",
        "n:parody" to "原作",
        "n:other" to "其他",
    )

    @Test
    fun `getTagCN translates both halves`() {
        assertEquals("艺术家:朝凪", TagTranslationUtil.getTagCN(arrayOf("artist", "asanagi"), sample))
        assertEquals("原作:东方Project", TagTranslationUtil.getTagCN("series:touhou project", sample))
        assertEquals("其他:全彩", TagTranslationUtil.getTagCN("misc:Full Color", sample))
    }

    @Test
    fun `getTagCN keeps the original half when only one side is known`() {
        assertEquals("艺术家:nobody", TagTranslationUtil.getTagCN("artist:nobody", sample))
        assertEquals("group:asanagi", TagTranslationUtil.getTagCN("group:asanagi", sample))
        assertEquals("date_added:123", TagTranslationUtil.getTagCN("date_added:123", sample))
    }

    @Test
    fun `getTagCN leaves odd shapes and a missing database alone`() {
        assertEquals("asanagi", TagTranslationUtil.getTagCN("asanagi", sample))
        // The value keeps its own colons: split at the first one only.
        assertEquals("艺术家:re:zero", TagTranslationUtil.getTagCN("artist:re:zero", sample))
        assertEquals("artist:asanagi", TagTranslationUtil.getTagCN("artist:asanagi", null))
    }

    @Test
    fun `getTagCNBody translates the value only`() {
        assertEquals("东方Project", TagTranslationUtil.getTagCNBody(arrayOf("series", "touhou project"), sample))
        assertEquals("nobody", TagTranslationUtil.getTagCNBody(arrayOf("artist", "nobody"), sample))
        assertEquals("全彩", TagTranslationUtil.getTagCNBody(arrayOf("full color"), sample))
        assertEquals("full color", TagTranslationUtil.getTagCNBody(arrayOf("full color"), null))
    }

    @Test
    fun `translateValue is the single-tag entry point for list chips`() {
        assertEquals("朝凪", TagTranslationUtil.translateValue("artist", "asanagi", sample))
        assertEquals("asanagi", TagTranslationUtil.translateValue("group", "asanagi", sample))
        assertEquals("asanagi", TagTranslationUtil.translateValue("artist", "asanagi", null))
    }
}
