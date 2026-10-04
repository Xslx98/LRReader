package com.lanraragi.reader.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Audit 2026-10-04 C21: a failed download names its reason on the card and in the notification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class DownloadFailureLabelsTest {

    private val res = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun knownReason_isAppendedToTheBaseText() {
        assertEquals("Failed · Storage full", withFailureReason(res, "Failed", DownloadFailureReason.NO_SPACE))
        assertEquals(
            "Vol 1 · Access denied — check the API key",
            withFailureReason(res, "Vol 1", DownloadFailureReason.AUTH)
        )
    }

    @Test
    fun unknownOrMissingReason_leavesTheBaseText() {
        assertEquals("Failed", withFailureReason(res, "Failed", DownloadFailureReason.UNKNOWN))
        assertEquals("Failed", withFailureReason(res, "Failed", null))
    }

    @Test
    fun everyReasonButUnknownHasALabel() {
        DownloadFailureReason.entries.filter { it != DownloadFailureReason.UNKNOWN }.forEach {
            assertEquals(true, it.labelRes() != null)
        }
    }
}
