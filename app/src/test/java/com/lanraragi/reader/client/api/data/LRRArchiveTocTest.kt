package com.lanraragi.reader.client.api.data

import com.lanraragi.reader.client.api.lrrJson
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `toc` shares a response with the reader's progress decision: whatever its
 * shape, the rest of the archive must still decode.
 */
class LRRArchiveTocTest {

    private fun decode(tocField: String): LRRArchive = lrrJson.decodeFromString(
        """{"arcid":"abc",$tocField"title":"T","pagecount":5,"progress":3}"""
    )

    @Test
    fun `reads the server's page and name pairs`() {
        val archive = decode(""""toc":[{"page":1,"name":"One"},{"page":13,"name":"Two"}],""")
        assertEquals(listOf(LRRTocEntry(1, "One"), LRRTocEntry(13, "Two")), archive.toc)
    }

    @Test
    fun `a missing toc from a server older than 0_9_70 is empty`() {
        assertEquals(emptyList<LRRTocEntry>(), decode("").toc)
    }

    @Test
    fun `a non-string name is kept as text`() {
        assertEquals(listOf(LRRTocEntry(2, "7")), decode(""""toc":[{"page":2,"name":7}],""").toc)
    }

    @Test
    fun `a null or missing name leaves the title for the fallback`() {
        val archive = decode(""""toc":[{"page":2,"name":null},{"page":4}],""")
        assertEquals(listOf(LRRTocEntry(2, ""), LRRTocEntry(4, "")), archive.toc)
    }

    @Test
    fun `any malformed toc yields empty and keeps the other fields`() {
        val malformed = listOf(
            "null",
            "\"chapters\"",
            "7",
            """{"1":"One"}""",
            "[1,2]",
            """[{"name":"no page"}]""",
            """[{"page":"three","name":"x"}]""",
            """[{"page":1,"name":["x"]}]""",
            """[{"page":1,"name":"good"},{"page":null,"name":"bad"}]""",
        )
        for (toc in malformed) {
            val archive = decode(""""toc":$toc,""")
            assertEquals("toc=$toc", emptyList<LRRTocEntry>(), archive.toc)
            assertEquals("toc=$toc", "T", archive.title)
            assertEquals("toc=$toc", 3, archive.progress)
        }
    }
}
