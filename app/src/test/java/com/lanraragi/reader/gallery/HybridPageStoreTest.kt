package com.lanraragi.reader.gallery

import com.lanraragi.reader.download.DownloadPageRepair
import com.lanraragi.reader.download.DurablePageWrite
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
        store = HybridPageStore(downloadDir, warmDir, DurablePageWrite(usableBytes = { Long.MAX_VALUE }, syncFile = {}))
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

    /** Audit 2026-10-06e P4-d: a damaged download-dir page goes to the download pipeline. */
    @Test
    fun handOverDamagedPage_marksItKeepsItAndReadsTheCacheFromNowOn() {
        setUp()
        var repairs = 0
        val store = HybridPageStore(
            downloadDir, warmDir, DurablePageWrite(usableBytes = { Long.MAX_VALUE }, syncFile = {}),
            onRepairRequested = { repairs++ },
        )
        val page = File(downloadDir, "0004.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertTrue(store.handOverDamagedPage(3, page))

        assertTrue(DownloadPageRepair.isMarked(page))
        assertArrayEquals("not deleted or replaced by the reader", byteArrayOf(1, 2, 3), page.readBytes())
        assertNull("page 3 now resolves to the reader cache", store.pageFile(3, "arc/003.png"))
        assertEquals(File(downloadDir, "0005.png"), store.pageFile(4, "arc/004.png"))
        assertEquals(1, repairs)
        assertFalse("handed over once per session", store.handOverDamagedPage(3, page))
        assertEquals(1, repairs)
    }

    @Test
    fun handOverDamagedPage_refusesAReaderCachePage() {
        setUp()
        val cached = File(warmDir, "page_3").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertFalse(store.handOverDamagedPage(3, cached))

        assertFalse(DownloadPageRepair.isMarked(cached))
        assertEquals(File(downloadDir, "0004.png"), store.pageFile(3, "arc/003.png"))
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
        val min = ReaderPageCache.MIN_IMAGE_SIZE.toInt()
        val small = File(downloadDir, "0001.jpg").apply { writeBytes(ByteArray(min)) }
        // A tiny blank page is still a page (was rejected by the old 1 KB floor).
        val big = File(downloadDir, "0002.jpg").apply { writeBytes(ByteArray(min + 1)) }
        assertFalse(HybridPageStore.isPresent(File(downloadDir, "missing.jpg")))
        assertFalse(HybridPageStore.isPresent(small))
        assertTrue(HybridPageStore.isPresent(big))
    }

    private fun jpegBytes(): ByteArray =
        ByteArray(4096) { 0 }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
}
