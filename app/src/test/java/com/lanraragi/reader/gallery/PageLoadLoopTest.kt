package com.lanraragi.reader.gallery

import com.lanraragi.framework.lib.image.DecodeResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Audit 2026-10-06d PERF-01: a page that does not decode is deleted and
 * fetched again only when the FILE is bad. A valid page the device cannot
 * decode (AVIF below API 31, JPEG XL, out of memory twice) fails at once
 * with its own message and stays on disk; a damaged page in the download
 * directory (hybrid) is never deleted.
 */
class PageLoadLoopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var cacheDir: File
    private lateinit var downloadDir: File
    private var fetches = 0
    private var decodes = 0
    private var retries = 0

    @Before
    fun setUp() {
        cacheDir = tmp.newFolder("cache")
        downloadDir = tmp.newFolder("dl")
    }

    /** Fake fetch that, like downloadPageToCache, writes [bytes] only when the file is absent. */
    private fun loop(
        file: File,
        bytes: ByteArray,
        sdkInt: Int = 30,
        decode: (Int) -> DecodeResult<String>,
    ) = PageLoadLoop(
        pageFile = { file },
        fetch = {
            if (!file.exists()) {
                fetches++
                file.writeBytes(bytes)
            }
        },
        decode = { decode(++decodes) },
        ownedByDownload = { it.parentFile != cacheDir },
        retryDelay = { retries++ },
        sdkInt = sdkInt,
    )

    private val failed = DecodeResult.Failed(IllegalStateException("decoder rejected the bytes"))

    @Test
    fun avifBelowApi31_failsAsUnsupported_keptAndNotRefetched() = runBlocking {
        val file = File(cacheDir, "page_0")
        val result = loop(file, PageFixtures.avif, sdkInt = 30) { failed }.run()

        assertEquals(PageLoadResult.Failed(PageFailure.Unsupported(PageImageFormat.AVIF)), result)
        assertEquals(1, fetches)
        assertEquals(1, decodes)
        assertEquals(0, retries)
        assertArrayEquals("valid page must stay on disk", PageFixtures.avif, file.readBytes())
    }

    @Test
    fun jpegXl_failsAsUnsupported_onAnyApi() = runBlocking {
        val file = File(cacheDir, "page_0")
        val result = loop(file, PageFixtures.jxl, sdkInt = 35) { failed }.run()

        assertEquals(PageLoadResult.Failed(PageFailure.Unsupported(PageImageFormat.JXL)), result)
        assertEquals(1, fetches)
        assertTrue(file.exists())
    }

    @Test
    fun outOfMemoryTwice_failsAsTooLarge_keptAndNotRefetched() = runBlocking {
        val file = File(cacheDir, "page_0")
        val result = loop(file, PageFixtures.damagedPng) { DecodeResult.OutOfMemory }.run()

        assertEquals(PageLoadResult.Failed(PageFailure.TooLarge), result)
        assertEquals(1, fetches)
        assertEquals(0, retries)
        assertTrue(file.exists())
    }

    @Test
    fun damagedCachePage_isDeletedAndRefetchedOnce() = runBlocking {
        val file = File(cacheDir, "page_0")
        val result = loop(file, PageFixtures.damagedPng) { failed }.run()

        assertEquals(PageLoadResult.Failed(PageFailure.Corrupt), result)
        assertEquals("one re-fetch after the damaged file", 2, fetches)
        assertEquals(2, decodes)
        assertEquals(1, retries)
        assertFalse("damaged cache page is dropped", file.exists())
    }

    @Test
    fun damagedCachePage_refetchThatDecodes_loads() = runBlocking {
        val file = File(cacheDir, "page_0")
        val result = loop(file, PageFixtures.damagedPng) { n ->
            if (n == 1) failed else DecodeResult.Ok("page")
        }.run()

        assertEquals(PageLoadResult.Loaded("page"), result)
        assertEquals(2, fetches)
    }

    @Test
    fun avifOnApi31_decoderRejects_isTreatedAsDamaged() = runBlocking {
        val file = File(cacheDir, "page_0")
        val result = loop(file, PageFixtures.avif, sdkInt = 31) { failed }.run()

        assertEquals(PageLoadResult.Failed(PageFailure.Corrupt), result)
        assertEquals(2, fetches)
    }

    @Test
    fun damagedDownloadDirPage_isNeverDeleted() = runBlocking {
        val file = File(downloadDir, "0001.png")
        val result = loop(file, PageFixtures.damagedPng) { failed }.run()

        assertEquals(PageLoadResult.Failed(PageFailure.Corrupt), result)
        assertEquals(1, fetches)
        assertEquals(0, retries)
        assertArrayEquals("download-dir page belongs to the worker", PageFixtures.damagedPng, file.readBytes())
    }

    @Test
    fun unsupportedDownloadDirPage_isKept() = runBlocking {
        val file = File(downloadDir, "0001.avif")
        val result = loop(file, PageFixtures.avif, sdkInt = 29) { failed }.run()

        assertEquals(PageLoadResult.Failed(PageFailure.Unsupported(PageImageFormat.AVIF)), result)
        assertTrue(file.exists())
    }

    @Test
    fun notAnImage_isStillDeletedAndRefetchedOnce() = runBlocking {
        val file = File(cacheDir, "page_0")
        val result = loop(file, ByteArray(64) { 0x41 }) { error("must not decode a non-image") }.run()

        assertEquals(PageLoadResult.Failed(PageFailure.NotImage), result)
        assertEquals(2, fetches)
        assertEquals(0, decodes)
    }

    @Test
    fun decodedPage_loadsWithoutRetry() = runBlocking {
        val file = File(cacheDir, "page_0")
        val result = loop(file, PageFixtures.damagedPng) { DecodeResult.Ok("page") }.run()

        assertEquals(PageLoadResult.Loaded("page"), result)
        assertEquals(1, fetches)
        assertEquals(0, retries)
    }

    @Test
    fun formatDetection_namesAvifHeifAndJxl() {
        fun detect(bytes: ByteArray) = File(tmp.root, "probe").apply { writeBytes(bytes) }
            .let(ReaderPageCache::detectImageFormat)
        assertEquals(PageImageFormat.AVIF, detect(PageFixtures.avif))
        assertEquals(PageImageFormat.JXL, detect(PageFixtures.jxl))
        assertEquals(PageImageFormat.PNG, detect(PageFixtures.damagedPng))
        val heic = byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray() + ByteArray(32)
        assertEquals(PageImageFormat.HEIF, detect(heic))
        assertFalse(PageImageFormat.AVIF.decodableOn(30))
        assertTrue(PageImageFormat.AVIF.decodableOn(31))
        assertTrue(PageImageFormat.HEIF.decodableOn(28))
        assertFalse(PageImageFormat.JXL.decodableOn(Int.MAX_VALUE - 1))
    }
}
