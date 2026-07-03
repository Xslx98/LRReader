package com.hippo.ehviewer.gallery

import com.lanraragi.reader.domain.Archive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ReadingContextStoreTest {

    private fun archive(arcid: String) = Archive(
        arcid = arcid, title = "t-$arcid", tags = emptyMap(), pagecount = 10,
        progress = 0, extension = "zip", filename = "f", thumbnailUrl = "",
        rating = 0f, isnew = false, lastreadtime = 0L, summary = null,
        serverProfileId = 7L,
    )

    private fun onlineCtx(anchor: String, index: Int) = ReadingContext.OnlineSearch(
        sourceProfileId = 7L, sourceBaseUrl = "http://server:3000",
        filter = "tag:x", category = null, sortby = "title", order = "asc",
        newonly = false, untaggedonly = false,
        anchorArcid = anchor, anchorIndex = index,
    )

    @Before
    fun setUp() = ReadingContextStore.clear()

    @Test
    fun `currentFor returns context only when anchor matches`() {
        ReadingContextStore.publish(onlineCtx("a".repeat(40), 3))
        assertEquals(3, (ReadingContextStore.currentFor("a".repeat(40)) as ReadingContext.OnlineSearch).anchorIndex)
        assertNull(ReadingContextStore.currentFor("b".repeat(40)))
    }

    @Test
    fun `publish overwrites previous context`() {
        ReadingContextStore.publish(onlineCtx("a".repeat(40), 3))
        ReadingContextStore.publish(
            ReadingContext.LocalList(
                kind = ReadingContext.LocalList.Kind.HISTORY,
                forwardArchives = listOf(archive("c".repeat(40))),
                anchorArcid = "c".repeat(40),
            )
        )
        assertNull(ReadingContextStore.currentFor("a".repeat(40)))
        assertEquals(
            ReadingContext.LocalList.Kind.HISTORY,
            (ReadingContextStore.currentFor("c".repeat(40)) as ReadingContext.LocalList).kind,
        )
    }

    @Test
    fun `advance moves the anchor for chained reads`() {
        ReadingContextStore.publish(onlineCtx("a".repeat(40), 3))
        ReadingContextStore.advance(onlineCtx("b".repeat(40), 4))
        assertNull(ReadingContextStore.currentFor("a".repeat(40)))
        assertEquals(4, (ReadingContextStore.currentFor("b".repeat(40)) as ReadingContext.OnlineSearch).anchorIndex)
    }
}
