package com.lanraragi.reader.updater

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer

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

    @Test
    fun targetFileSanitisesTheNameAndDropsStaleApks() {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val stale = File(dir, "LRReader-v1.20.0.apk").apply { writeText("old") }
        val release = GhRelease(tagName = "v1.28.0", assets = listOf(GhReleaseAsset(name = "../evil.apk")))

        val dest = ApkDownloader.targetFile(context, release)

        assertEquals(ApkIntegrity.FALLBACK_NAME, dest!!.name)
        assertEquals(dir.canonicalFile, dest.parentFile!!.canonicalFile)
        assertFalse("stale APK must be removed", stale.exists())
    }

    // ── ApkDownloader.download (audit C46) ────────────────────────────

    private fun serve(body: ByteArray): MockWebServer = MockWebServer().apply {
        enqueue(MockResponse().setBody(Buffer().write(body)))
        start()
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun runDownload(server: MockWebServer, asset: GhReleaseAsset, dest: File): List<DownloadProgress> =
        runBlocking {
            ApkDownloader.download(asset, dest, OkHttpClient(), isTrustedUrl = { true }).toList()
        }.also { server.shutdown() }

    @Test
    fun downloadMatchingSizeAndDigest_succeedsAndKeepsTheFile() {
        val apk = ByteArray(300_000) { it.toByte() }
        val server = serve(apk)
        val dest = File(context.cacheDir, "ok.apk")
        val asset = GhReleaseAsset(
            browserDownloadUrl = server.url("/a.apk").toString(), size = apk.size.toLong(),
            digest = "sha256:${sha256(apk)}",
        )

        val events = runDownload(server, asset, dest)

        assertEquals(DownloadProgress.Success, events.last())
        assertTrue(dest.readBytes().contentEquals(apk))
    }

    @Test
    fun downloadWithWrongDigest_failsAndDeletesTheFile() {
        val apk = ByteArray(1_000) { 7 }
        val server = serve(apk)
        val dest = File(context.cacheDir, "bad.apk")
        val asset = GhReleaseAsset(
            browserDownloadUrl = server.url("/a.apk").toString(), size = apk.size.toLong(),
            digest = "sha256:" + "00".repeat(32),
        )

        val events = runDownload(server, asset, dest)

        assertTrue(events.last() is DownloadProgress.Failed)
        assertFalse(dest.exists())
    }

    @Test
    fun downloadWithWrongSize_failsAndDeletesTheFile() {
        val apk = ByteArray(1_000) { 7 }
        val server = serve(apk)
        val dest = File(context.cacheDir, "short.apk")
        val asset = GhReleaseAsset(browserDownloadUrl = server.url("/a.apk").toString(), size = 2_000)

        val events = runDownload(server, asset, dest)

        assertTrue(events.last() is DownloadProgress.Failed)
        assertFalse(dest.exists())
    }

    @Test
    fun downloadFromUntrustedUrl_failsWithoutARequest() {
        val server = serve(ByteArray(10))
        val asset = GhReleaseAsset(browserDownloadUrl = server.url("/a.apk").toString())

        val events = runBlocking {
            ApkDownloader.download(asset, File(context.cacheDir, "x.apk"), OkHttpClient()).toList()
        }

        val failed = events.single() as DownloadProgress.Failed
        assertTrue(failed.cause is SecurityException)
        assertEquals(0, server.requestCount)
        server.shutdown()
    }
}
