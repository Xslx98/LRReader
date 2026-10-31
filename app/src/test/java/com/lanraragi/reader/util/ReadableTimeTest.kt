package com.lanraragi.reader.util

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [ReadableTime.shortInterval]: the download notification's remaining-time text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ReadableTimeTest {

    private val resources = ApplicationProvider.getApplicationContext<android.app.Application>().resources

    private fun interval(millis: Long) = ReadableTime.shortInterval(resources, millis)

    @Test
    fun picksTheLargestUnitExceededOneAndAHalfTimes() {
        assertEquals("3 mins", interval(200_000L))
        assertEquals("2 days", interval(2L * 24 * 60 * 60 * 1000 + 1))
    }

    @Test
    fun stopsAtOneAndAHalfOfAUnit() {
        // 90 s is not > 1.5 min, so it stays in seconds.
        assertEquals("90 secs", interval(90_000L))
        assertEquals("1 min", interval(90_001L))
    }

    @Test
    fun belowOneAndAHalfSecondsFallsBackToSeconds() {
        assertEquals("1 sec", interval(1_000L))
        assertEquals("0 secs", interval(0L))
    }
}
