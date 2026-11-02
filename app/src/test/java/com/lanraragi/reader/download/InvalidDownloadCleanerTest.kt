package com.lanraragi.reader.download

import com.lanraragi.reader.storage.StorageFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [InvalidDownloadCleaner]: only empty directories go, files and filled
 * directories stay, the removals are logged into the location, and the
 * listener sees the total, every step and the count.
 */
class InvalidDownloadCleanerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val events = mutableListOf<String>()
    private val listener = object : InvalidDownloadCleaner.Listener {
        override fun onTotal(total: Int) { events += "total $total" }
        override fun onProgress(checked: Int) { events += "checked $checked" }
        override fun onDone(removed: Int) { events += "done $removed" }
    }

    @Test
    fun clean_removesOnlyEmptyDirectories_andLogsThem() {
        val root = tmp.newFolder("downloads")
        File(root, "empty").mkdir()
        File(root, "filled").mkdir()
        File(root, "filled/1.jpg").writeText("x")
        File(root, "loose.txt").writeText("x")

        val removed = InvalidDownloadCleaner().clean(StorageFile(root), listener)

        assertEquals(1, removed)
        assertFalse(File(root, "empty").exists())
        assertTrue(File(root, "filled/1.jpg").exists())
        assertTrue(File(root, "loose.txt").exists())
        val log = root.listFiles()!!.single { it.name.startsWith("delfile-") }
        assertEquals("Empty directory: empty\n", log.readText())
        assertEquals(listOf("total 3", "checked 1", "checked 2", "checked 3"), events)
    }

    @Test
    fun clean_withoutALocation_removesNothing() {
        assertEquals(0, InvalidDownloadCleaner().clean(null, listener))
        assertEquals(0, InvalidDownloadCleaner().clean(StorageFile(File(tmp.root, "missing")), listener))
        assertTrue(events.isEmpty())
    }

    @Test
    fun clean_withNothingEmpty_writesNoLog() {
        val root = tmp.newFolder("downloads")
        File(root, "filled").mkdir()
        File(root, "filled/1.jpg").writeText("x")

        assertEquals(0, InvalidDownloadCleaner().clean(StorageFile(root), listener))
        assertEquals(listOf("filled"), root.list()!!.toList())
    }
}
