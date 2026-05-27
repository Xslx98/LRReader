package com.hippo.ehviewer.updater

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [DownloadProgress] percent math + [ApkDownloader.targetFile] path generation.
 *
 * The actual [ApkDownloader.download] coroutine flow is integration-level (network + Flow +
 * OkHttp + file IO) and is verified manually via smoke testing in Task 7.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ApkDownloadTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        File(context.cacheDir, "updates").deleteRecursively()
    }

    // ── DownloadProgress.percent ──────────────────────────────────────

    @Test
    fun percentZeroWhenTotalUnknown() {
        val p = DownloadProgress.InProgress(bytesDownloaded = 1024, totalBytes = -1)
        assertEquals(0, p.percent)
    }

    @Test
    fun percentZeroWhenTotalZero() {
        val p = DownloadProgress.InProgress(bytesDownloaded = 1024, totalBytes = 0)
        assertEquals(0, p.percent)
    }

    @Test
    fun percentHalfwayThrough() {
        val p = DownloadProgress.InProgress(bytesDownloaded = 5_000_000, totalBytes = 10_000_000)
        assertEquals(50, p.percent)
    }

    @Test
    fun percentFullyDone() {
        val p = DownloadProgress.InProgress(bytesDownloaded = 8_000_000, totalBytes = 8_000_000)
        assertEquals(100, p.percent)
    }

    @Test
    fun percentSmallStart() {
        // Just-started: 256 KB of 8 MB ≈ 3%
        val p = DownloadProgress.InProgress(bytesDownloaded = 256_000, totalBytes = 8_000_000)
        assertEquals(3, p.percent)
    }

    // ── ApkDownloader.targetFile ──────────────────────────────────────

    @Test
    fun targetFileReturnsNullWhenNoApkAsset() {
        val release = GhRelease(tagName = "v1.14.0", assets = emptyList())
        assertNull(ApkDownloader.targetFile(context, release))
    }

    @Test
    fun targetFileReturnsNullWhenAssetsHaveNoApk() {
        val release = GhRelease(
            tagName = "v1.14.0",
            assets = listOf(
                GhReleaseAsset(name = "checksums.txt"),
                GhReleaseAsset(name = "source.zip"),
            ),
        )
        assertNull(ApkDownloader.targetFile(context, release))
    }

    @Test
    fun targetFilePicksFirstApkAsset() {
        val release = GhRelease(
            tagName = "v1.14.0",
            assets = listOf(
                GhReleaseAsset(name = "checksums.txt"),
                GhReleaseAsset(name = "LRReader-v1.14.0.apk"),
            ),
        )
        val dest = ApkDownloader.targetFile(context, release)
        assertNotNull(dest)
        assertEquals("LRReader-v1.14.0.apk", dest!!.name)
        // Must be under cacheDir/updates/
        assertTrue(
            "expected dest under cacheDir/updates, was ${dest.absolutePath}",
            dest.absolutePath.contains("${File.separator}cache${File.separator}updates${File.separator}"),
        )
    }

    @Test
    fun targetFileCreatesUpdatesSubdir() {
        val release = GhRelease(
            tagName = "v1.14.0",
            assets = listOf(GhReleaseAsset(name = "LRReader-v1.14.0.apk")),
        )
        val dest = ApkDownloader.targetFile(context, release)
        assertNotNull(dest)
        val parent = dest!!.parentFile
        assertNotNull(parent)
        assertTrue("expected ${parent.absolutePath} to exist", parent.exists())
        assertTrue("expected ${parent.absolutePath} to be a directory", parent.isDirectory)
        assertEquals("updates", parent.name)
    }
}
