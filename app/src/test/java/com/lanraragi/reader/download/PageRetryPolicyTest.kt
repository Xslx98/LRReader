package com.lanraragi.reader.download

import android.system.ErrnoException
import android.system.OsConstants
import com.lanraragi.reader.download.PageRetryPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.random.Random

/** Audit 2026-10-04 C21: page failure classification and retry decisions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class PageRetryPolicyTest {

    private val noJitter = object : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextDouble() = 0.5
    }

    @Test
    fun permanentHttpFailures_abortOnFirstAttempt() {
        assertEquals(Decision.Abort(DownloadFailureReason.AUTH), PageRetryPolicy.decide(HttpStatusException(401), 1))
        assertEquals(Decision.Abort(DownloadFailureReason.AUTH), PageRetryPolicy.decide(HttpStatusException(403), 1))
        assertEquals(Decision.Abort(DownloadFailureReason.NOT_FOUND), PageRetryPolicy.decide(HttpStatusException(404), 1))
        assertEquals(Decision.Abort(DownloadFailureReason.NOT_FOUND), PageRetryPolicy.decide(HttpStatusException(410), 1))
    }

    @Test
    fun diskFull_abortsWhetherErrnoOrMessage() {
        val errno = IOException("write failed", ErrnoException("write", OsConstants.ENOSPC))
        val message = IOException("write failed: ENOSPC (No space left on device)")
        assertEquals(Decision.Abort(DownloadFailureReason.NO_SPACE), PageRetryPolicy.decide(errno, 1))
        assertEquals(Decision.Abort(DownloadFailureReason.NO_SPACE), PageRetryPolicy.decide(message, 1))
    }

    @Test
    fun transientFailures_backOffExponentiallyThenFail() {
        val e = HttpStatusException(502)
        assertEquals(Decision.Retry(1_000), PageRetryPolicy.decide(e, 1, noJitter))
        assertEquals(Decision.Retry(2_000), PageRetryPolicy.decide(e, 2, noJitter))
        assertEquals(Decision.Retry(4_000), PageRetryPolicy.decide(e, 3, noJitter))
        assertEquals(Decision.Fail(DownloadFailureReason.SERVER), PageRetryPolicy.decide(e, 4, noJitter))
        assertEquals(Decision.Retry(1_000), PageRetryPolicy.decide(SocketTimeoutException(), 1, noJitter))
        assertEquals(PageRetryPolicy.MAX_BACKOFF_MS, PageRetryPolicy.backoff(20, noJitter))
    }

    @Test
    fun backoffJitter_staysWithinTwentyPercent() {
        val random = Random(42)
        repeat(100) {
            val d = PageRetryPolicy.backoff(3, random)
            assertTrue("delay $d", d in 3_200..4_800)
        }
    }

    @Test
    fun retryAfter_overridesBackoffAndIsCapped() {
        assertEquals(Decision.Retry(7_000), PageRetryPolicy.decide(HttpStatusException(429, 7_000), 1))
        assertEquals(
            Decision.Retry(PageRetryPolicy.MAX_RETRY_AFTER_MS),
            PageRetryPolicy.decide(HttpStatusException(503, 3_600_000), 1)
        )
        assertEquals(7_000L, PageRetryPolicy.parseRetryAfter(" 7 "))
        assertEquals(null, PageRetryPolicy.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    @Test
    fun corruptAndOther4xx_keepTheShortPolicy() {
        val corrupt = CorruptPageException("not an image")
        assertEquals(Decision.Retry(PageRetryPolicy.BASE_DELAY_MS), PageRetryPolicy.decide(corrupt, 1))
        assertEquals(Decision.Fail(DownloadFailureReason.CORRUPT), PageRetryPolicy.decide(corrupt, 2))
        assertEquals(Decision.Fail(DownloadFailureReason.UNKNOWN), PageRetryPolicy.decide(HttpStatusException(400), 1))
        assertEquals(Decision.Retry(PageRetryPolicy.BASE_DELAY_MS), PageRetryPolicy.decide(IllegalStateException(), 1))
    }
}
