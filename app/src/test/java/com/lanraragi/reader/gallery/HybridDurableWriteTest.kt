package com.lanraragi.reader.gallery

import com.lanraragi.reader.download.DurablePageWrite
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Audit REL-04: a page the reader writes into a download directory (hybrid
 * session, or a warm page adopted there) gets the worker's guarantees —
 * fsync before the rename, free-space floor, size cap — while the
 * disposable reader cache keeps its unsynced write.
 */
class HybridDurableWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

    private lateinit var downloadDir: File
    private lateinit var warmDir: File

    /** Files seen by the sync seam, with their size and whether the final page already existed. */
    private val synced = mutableListOf<Triple<File, Long, Boolean>>()
    private var usable = Long.MAX_VALUE
    private var target: File? = null

    private fun policy(maxPageBytes: Long = DurablePageWrite.MAX_PAGE_SIZE) = DurablePageWrite(
        usableBytes = { usable },
        syncFile = { out ->
            // The temp file is the only *.tmp in the directory at sync time.
            val tmpFile = downloadDir.listFiles()!!.single { it.name.endsWith(".tmp") }
            synced += Triple(tmpFile, out.channel.size(), target?.exists() == true)
        },
        maxPageBytes = maxPageBytes,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloadDir = tmp.newFolder("dl")
        warmDir = tmp.newFolder("warm")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun store(policy: DurablePageWrite = policy()) = HybridPageStore(downloadDir, warmDir, policy)

    private fun download(file: File, durable: DurablePageWrite?) {
        target = file
        ReaderPageCache.downloadToFile(client, server.url("/page").toString(), file, durable = durable)
    }

    @Test
    fun durableWriteFor_isSetForDownloadDirPagesOnly() {
        val policy = policy()
        val store = store(policy)
        assertSame(policy, store.durableWriteFor(File(downloadDir, "0001.jpg")))
        assertNull(store.durableWriteFor(File(warmDir, "page_0")))
    }

    @Test
    fun hybridWrite_syncsTheCompleteTempFileBeforeTheRename() {
        val bytes = jpegBytes(8192)
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val page = File(downloadDir, "0001.jpg")

        download(page, store().durableWriteFor(page))

        assertEquals("one fsync per page", 1, synced.size)
        val (_, sizeAtSync, finalExisted) = synced.single()
        assertEquals("fsync must see every byte", bytes.size.toLong(), sizeAtSync)
        assertFalse("fsync must come before the rename", finalExisted)
        assertArrayEquals(bytes, page.readBytes())
    }

    @Test
    fun cacheWrite_isNotSynced() {
        server.enqueue(MockResponse().setBody(Buffer().write(jpegBytes(8192))))
        val page = File(warmDir, "page_0")

        download(page, store().durableWriteFor(page))

        assertTrue(page.isFile)
        assertTrue("the disposable cache keeps its unsynced write", synced.isEmpty())
    }

    @Test
    fun hybridWrite_belowFreeSpaceFloor_failsWithoutFetching() {
        usable = DurablePageWrite.MIN_FREE_BYTES - 1
        server.enqueue(MockResponse().setBody(Buffer().write(jpegBytes(8192))))
        val page = File(downloadDir, "0001.jpg")

        assertThrows(IOException::class.java) { download(page, store().durableWriteFor(page)) }

        assertEquals("no request below the floor", 0, server.requestCount)
        assertFalse(page.exists())
    }

    @Test
    fun hybridWrite_overSizeCap_failsAndLeavesNothing() {
        server.enqueue(MockResponse().setBody(Buffer().write(jpegBytes(256 * 1024))))
        val page = File(downloadDir, "0001.jpg")

        assertThrows(IOException::class.java) {
            download(page, store(policy(maxPageBytes = 64 * 1024)).durableWriteFor(page))
        }

        assertFalse(page.exists())
        assertTrue(downloadDir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun adoptWarmCachedPage_syncsBeforeTheRename() {
        val bytes = jpegBytes(4096)
        File(warmDir, "page_2").writeBytes(bytes)
        val page = File(downloadDir, "0003.jpg")
        target = page

        assertTrue(store().adoptWarmCachedPage(2, page))

        assertEquals(1, synced.size)
        val (_, sizeAtSync, finalExisted) = synced.single()
        assertEquals(bytes.size.toLong(), sizeAtSync)
        assertFalse(finalExisted)
        assertArrayEquals(bytes, page.readBytes())
    }

    @Test
    fun adoptWarmCachedPage_belowFreeSpaceFloor_keepsTheWarmCopy() {
        usable = DurablePageWrite.MIN_FREE_BYTES - 1
        val warm = File(warmDir, "page_2").apply { writeBytes(jpegBytes(4096)) }
        val page = File(downloadDir, "0003.jpg")

        assertFalse(store().adoptWarmCachedPage(2, page))

        assertFalse(page.exists())
        assertTrue(warm.exists())
    }

    private fun jpegBytes(size: Int): ByteArray =
        ByteArray(size) { (it % 251).toByte() }.also {
            it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte()
        }
}
