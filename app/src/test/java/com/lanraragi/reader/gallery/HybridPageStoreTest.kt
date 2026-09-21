package com.lanraragi.reader.gallery

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HybridPageStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var downloadDir: File
    private lateinit var warmDir: File
    private lateinit var store: HybridPageStore

    private fun setUp(createDownloadDir: Boolean = true) {
        downloadDir = if (createDownloadDir) tmp.newFolder("dl") else File(tmp.root, "dl")
        warmDir = tmp.newFolder("warm")
        store = HybridPageStore(downloadDir, warmDir)
    }

    @Test
    fun pageFile_usesWorkerNamingInDownloadDir() {
        setUp()
        assertEquals(File(downloadDir, "0004.png"), store.pageFile(3, "arc/003.png"))
    }

    @Test
    fun pageFile_isNullUntilPagePathIsKnown() {
        setUp()
        assertNull(store.pageFile(3, null))
    }

    @Test
    fun ensureDir_createsDirAndNoMedia() {
        setUp(createDownloadDir = false)
        assertFalse(downloadDir.exists())
        store.ensureDir()
        assertTrue(downloadDir.isDirectory)
        assertTrue(File(downloadDir, ".nomedia").isFile)
    }

    @Test
    fun ensureDir_isIdempotent() {
        setUp()
        store.ensureDir()
        store.ensureDir()
        assertTrue(File(downloadDir, ".nomedia").isFile)
    }

    @Test
    fun adoptWarmCachedPage_movesValidWarmPageIntoTarget() {
        setUp()
        val bytes = jpegBytes()
        File(warmDir, "page_2").writeBytes(bytes)
        val target = File(downloadDir, "0003.jpg")

        assertTrue(store.adoptWarmCachedPage(2, target))
        assertArrayEquals(bytes, target.readBytes())
        assertFalse(File(warmDir, "page_2").exists())
        assertTrue(downloadDir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun adoptWarmCachedPage_ignoresMissingWarmPage() {
        setUp()
        assertFalse(store.adoptWarmCachedPage(2, File(downloadDir, "0003.jpg")))
    }

    @Test
    fun adoptWarmCachedPage_ignoresTruncatedWarmPage() {
        setUp()
        File(warmDir, "page_2").writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        assertFalse(store.adoptWarmCachedPage(2, File(downloadDir, "0003.jpg")))
        assertTrue(File(warmDir, "page_2").exists())
    }

    @Test
    fun adoptWarmCachedPage_ignoresNonImageWarmPage() {
        setUp()
        File(warmDir, "page_2").writeBytes(ByteArray(4096) { 0 })
        assertFalse(store.adoptWarmCachedPage(2, File(downloadDir, "0003.jpg")))
    }

    @Test
    fun isPresent_requiresMoreThanMinImageSize() {
        setUp()
        val small = File(downloadDir, "0001.jpg").apply { writeBytes(ByteArray(1024)) }
        val big = File(downloadDir, "0002.jpg").apply { writeBytes(ByteArray(1025)) }
        assertFalse(HybridPageStore.isPresent(File(downloadDir, "missing.jpg")))
        assertFalse(HybridPageStore.isPresent(small))
        assertTrue(HybridPageStore.isPresent(big))
    }

    private fun jpegBytes(): ByteArray =
        ByteArray(4096) { 0 }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
}
