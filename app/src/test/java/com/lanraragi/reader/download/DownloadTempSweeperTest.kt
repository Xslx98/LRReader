package com.lanraragi.reader.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Audit 2026-10-04 C47: stale page temp files are swept, fresh ones and pages are not. */
class DownloadTempSweeperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(dir: File, name: String, modified: Long) =
        File(dir, name).apply { writeText("x"); setLastModified(modified) }

    @Test
    fun sweep_deletesOnlyStaleTempFiles() {
        val dir = tmp.newFolder("Vol 1")
        val now = 100L * 60 * 1000
        val stale = now - DownloadTempSweeper.STALE_AFTER_MS - 1000
        val staleTmp = file(dir, "00001.jpg.0f1e.tmp", stale)
        val freshTmp = file(dir, "00002.jpg.7.tmp", now - 1000)
        val page = file(dir, "00003.jpg", stale)
        val sub = File(dir, "nested.tmp").apply { mkdirs(); setLastModified(stale) }

        assertEquals(1, DownloadTempSweeper.sweep(dir, now))

        assertFalse(staleTmp.exists())
        assertTrue(freshTmp.exists())
        assertTrue(page.exists())
        assertTrue(sub.exists())
    }
}
