package com.lanraragi.reader.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Audit 2026-10-06e P4-d: the reader's hand-over of a damaged download-dir page. */
class DownloadPageRepairTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun markClearAndPending() {
        val dir = tmp.newFolder("dl")
        val page = File(dir, "0002.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertFalse(DownloadPageRepair.hasPending(dir))

        assertTrue(DownloadPageRepair.mark(page))
        assertTrue(DownloadPageRepair.mark(page)) // idempotent

        assertTrue(DownloadPageRepair.isMarked(page))
        assertTrue(DownloadPageRepair.hasPending(dir))
        assertTrue(DownloadPageRepair.isMarker(DownloadPageRepair.markerFor(page)))
        assertFalse(DownloadPageRepair.isMarker(page))
        assertEquals("the page itself is untouched", 3L, page.length())

        DownloadPageRepair.clear(page)
        assertFalse(DownloadPageRepair.isMarked(page))
        assertFalse(DownloadPageRepair.hasPending(dir))
    }

    @Test
    fun requeue_onlyAFinishedDownload() {
        val restarted = mutableListOf<String>()
        for (state in DownloadState.entries) {
            val asked = DownloadPageRepair.requeueIfFinished("a", { state }) { restarted += "$state" }
            assertEquals("$state", state == DownloadState.FINISH, asked)
        }
        // Paused, failed, queued, running or gone downloads are left alone.
        assertEquals(listOf("${DownloadState.FINISH}"), restarted)
    }
}
