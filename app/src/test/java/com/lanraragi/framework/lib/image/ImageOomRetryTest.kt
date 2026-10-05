package com.lanraragi.framework.lib.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit 2026-10-04 C11 (user ruling): an OutOfMemoryError while decoding a page
 * retries once at sample x2, then yields null ("decode failed"), never a spinner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ImageOomRetryTest {

    @Test
    fun firstAttemptSucceeds_noRewindNoRetry() {
        val multipliers = ArrayList<Int>()
        var rewinds = 0
        val out = Image.retryOnOutOfMemory(rewind = { rewinds++ }) { m -> multipliers += m; "ok" }
        assertEquals("ok", out)
        assertEquals(listOf(1), multipliers)
        assertEquals(0, rewinds)
    }

    @Test
    fun oomOnce_rewindsAndRetriesAtDoubleSample() {
        val multipliers = ArrayList<Int>()
        var rewinds = 0
        val out = Image.retryOnOutOfMemory(rewind = { rewinds++ }) { m ->
            multipliers += m
            if (m == 1) throw OutOfMemoryError("page too big") else "half"
        }
        assertEquals("half", out)
        assertEquals(listOf(1, Image.OOM_RETRY_MULTIPLIER), multipliers)
        assertEquals(1, rewinds)
    }

    @Test
    fun oomTwice_givesNull() {
        var calls = 0
        val out = Image.retryOnOutOfMemory<String>(rewind = {}) { calls++; throw OutOfMemoryError("still too big") }
        assertNull(out)
        assertEquals(2, calls)
    }

    @Test
    fun exception_givesNullWithoutRetry() {
        var calls = 0
        val out = Image.retryOnOutOfMemory<String>(rewind = {}) { calls++; throw IllegalStateException("bad data") }
        assertNull(out)
        assertEquals(1, calls)
    }
}
