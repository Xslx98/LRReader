package com.lanraragi.reader.archive

import com.lanraragi.reader.domain.TagGroup
import com.lanraragi.reader.server.ServerConnection
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [TagEditSaver]: the edit goes to the source profile's server, only the
 * user's change is applied on top of the server's current string, explicit
 * `misc:` tags stay explicit, and the follow-up sees the server's old string.
 */
class TagEditSaverTest {

    private val conn = ServerConnection(9L, "http://lrr.test", OkHttpClient())

    private class FakeWriter(private val raw: String?, private val failWrite: Boolean = false) : TagWriter {
        val calls = mutableListOf<String>()

        override suspend fun write(client: OkHttpClient, baseUrl: String, id: String, tags: String) {
            calls += "write $baseUrl $id [$tags]"
            if (failWrite) throw IOException("write failed")
        }

        override suspend fun readRaw(client: OkHttpClient, baseUrl: String, id: String): String? {
            calls += "read $baseUrl $id"
            return raw ?: throw IOException("read failed")
        }

        override suspend fun afterWrite(client: OkHttpClient, baseUrl: String, id: String, oldTags: String, newTags: String) {
            calls += "after [$oldTags] -> [$newTags]"
        }
    }

    @Test
    fun save_mergesTheEditIntoTheServersCurrentString() = runTest {
        val asked = mutableListOf<Long>()
        val writer = FakeWriter(raw = "artist:a, rating:⭐⭐, misc:x")

        TagEditSaver { asked += it; conn }.save(
            9L, writer, "id1",
            opened = listOf(TagGroup("artist", listOf("a")), TagGroup("misc", listOf("x"))),
            edited = listOf(TagGroup("artist", listOf("a", "b")), TagGroup("misc", listOf("x"))),
        )

        assertEquals(listOf(9L), asked)
        val merged = "artist:a, rating:⭐⭐, misc:x, artist:b"
        assertEquals(
            listOf(
                "read http://lrr.test id1",
                "write http://lrr.test id1 [$merged]",
                "after [artist:a, rating:⭐⭐, misc:x] -> [$merged]",
            ),
            writer.calls,
        )
    }

    @Test
    fun save_unreadableServerString_writesTheEditAsIs() = runTest {
        val writer = FakeWriter(raw = null)

        TagEditSaver { conn }.save(
            9L, writer, "id1",
            opened = listOf(TagGroup("misc", listOf("y"))),
            edited = listOf(TagGroup("misc", listOf("y", "z"))),
        )

        assertEquals(listOf("read http://lrr.test id1", "write http://lrr.test id1 [y, z]", "after [y] -> [y, z]"), writer.calls)
    }

    @Test
    fun save_failedWrite_throwsWithoutFollowUp() = runTest {
        val writer = FakeWriter(raw = "a:1", failWrite = true)

        try {
            TagEditSaver { conn }.save(9L, writer, "id1", listOf(TagGroup("a", listOf("1"))), emptyList())
            fail("expected the write failure")
        } catch (expected: IOException) {
            assertEquals("write failed", expected.message)
        }
        assertTrue(writer.calls.none { it.startsWith("after") })
    }
}
