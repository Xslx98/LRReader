package com.lanraragi.reader.reading

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.client.api.data.LRRTocEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ChapterStoreTest {

    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("chapter_store_test", Context.MODE_PRIVATE)
    private val store = ChapterStore(prefs)

    private val toc = listOf(LRRTocEntry(1, "Opening"), LRRTocEntry(13, "章节 2"))

    @Test
    fun `chapters survive a new store over the same file, with pages as the server sent them`() {
        store.update(1L, "arc", toc + LRRTocEntry(9999, "past the end"))

        assertEquals(toc + LRRTocEntry(9999, "past the end"), ChapterStore(prefs).read(1L, "arc"))
    }

    @Test
    fun `entries are separate per profile and per archive`() {
        store.update(1L, "arc", toc)

        assertEquals(emptyList<LRRTocEntry>(), store.read(2L, "arc"))
        assertEquals(emptyList<LRRTocEntry>(), store.read(1L, "other"))
    }

    @Test
    fun `an archive without chapters writes nothing`() {
        store.update(1L, "arc", emptyList())

        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `the server's next answer replaces the entry, and an empty answer clears it`() {
        store.update(1L, "arc", toc)

        store.update(1L, "arc", listOf(LRRTocEntry(2, "Edited")))
        assertEquals(listOf(LRRTocEntry(2, "Edited")), store.read(1L, "arc"))

        store.update(1L, "arc", emptyList())
        assertEquals(emptyList<LRRTocEntry>(), store.read(1L, "arc"))
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `an unreadable value reads as no chapters`() {
        store.update(1L, "arc", toc)
        val key = prefs.all.keys.single()

        listOf("{not json", """{"page":1}""", """[{"name":"no page"}]""").forEach { corrupt ->
            prefs.edit().putString(key, corrupt).commit()
            assertEquals(corrupt, emptyList<LRRTocEntry>(), store.read(1L, "arc"))
        }
    }

    @Test
    fun `remove drops only the named rows`() {
        store.update(1L, "a", toc)
        store.update(1L, "b", toc)
        store.update(2L, "a", toc)

        store.remove(listOf(1L to "a", 1L to "b", 1L to "never stored"))

        assertEquals(emptyList<LRRTocEntry>(), store.read(1L, "a"))
        assertEquals(emptyList<LRRTocEntry>(), store.read(1L, "b"))
        assertEquals(toc, store.read(2L, "a"))
    }

    @Test
    fun `removeProfile leaves a profile whose id starts with the same digits`() {
        store.update(1L, "a", toc)
        store.update(11L, "a", toc)

        store.removeProfile(1L)

        assertFalse(store.read(11L, "a").isEmpty())
        assertTrue(store.read(1L, "a").isEmpty())
    }
}
