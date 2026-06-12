package com.hippo.ehviewer.ui.scene.download

import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.unifile.UniFile
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DownloadDirCache] (DL-12): one resolution per arcid shared by
 * all thumbnail binds, with null/failed resolutions evicted so a later bind
 * retries, and explicit invalidation for remove/replace/reload paths.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadDirCacheTest {

    private val dirA: UniFile = requireNotNull(UniFile.fromFile(File("build/tmp/dl-dir-cache/a")))
    private val dirB: UniFile = requireNotNull(UniFile.fromFile(File("build/tmp/dl-dir-cache/b")))

    private fun info(id: String) = DownloadInfo().apply {
        arcid = id
        title = "title-$id"
    }

    @Test
    fun sameArcid_sharesOneResolution() = runTest {
        var calls = 0
        val cache = DownloadDirCache(this) { calls++; dirA }

        val first = cache.futureFor(info("a"))
        val second = cache.futureFor(info("a"))
        assertSame(first, second)

        runCurrent()
        assertEquals(1, calls)
        assertTrue(first.isDone)
        assertSame(dirA, first.get())

        // Re-binding after completion still reuses the cached future.
        assertSame(first, cache.futureFor(info("a")))
        assertEquals(1, calls)
    }

    @Test
    fun distinctArcids_resolveIndependently() = runTest {
        val cache = DownloadDirCache(this) { info ->
            if (info.arcid == "a") dirA else dirB
        }

        val futureA = cache.futureFor(info("a"))
        val futureB = cache.futureFor(info("b"))
        assertNotSame(futureA, futureB)

        runCurrent()
        assertSame(dirA, futureA.get())
        assertSame(dirB, futureB.get())
    }

    @Test
    fun nullResolution_isEvictedSoTheNextBindRetries() = runTest {
        var calls = 0
        val cache = DownloadDirCache(this) {
            calls++
            if (calls == 1) null else dirA
        }

        val first = cache.futureFor(info("a"))
        runCurrent()
        assertTrue(first.isDone)
        assertNull(first.get())

        val second = cache.futureFor(info("a"))
        assertNotSame(first, second)
        runCurrent()
        assertEquals(2, calls)
        assertSame(dirA, second.get())
    }

    @Test
    fun failedResolution_completesNullAndRetries() = runTest {
        var calls = 0
        val cache = DownloadDirCache(this) {
            calls++
            if (calls == 1) throw IllegalStateException("storage gone") else dirA
        }

        val first = cache.futureFor(info("a"))
        runCurrent()
        assertTrue(first.isDone)
        assertNull(first.get())

        val second = cache.futureFor(info("a"))
        runCurrent()
        assertEquals(2, calls)
        assertSame(dirA, second.get())
    }

    @Test
    fun invalidate_forcesReResolution() = runTest {
        var calls = 0
        val cache = DownloadDirCache(this) { calls++; dirA }

        val first = cache.futureFor(info("a"))
        runCurrent()
        cache.invalidate("a")

        val second = cache.futureFor(info("a"))
        assertNotSame(first, second)
        runCurrent()
        assertEquals(2, calls)
        assertSame(dirA, second.get())
    }

    @Test
    fun clear_dropsEveryEntry() = runTest {
        var calls = 0
        val cache = DownloadDirCache(this) { calls++; dirA }

        cache.futureFor(info("a"))
        cache.futureFor(info("b"))
        runCurrent()
        assertEquals(2, calls)

        cache.clear()
        cache.futureFor(info("a"))
        cache.futureFor(info("b"))
        runCurrent()
        assertEquals(4, calls)
    }
}
