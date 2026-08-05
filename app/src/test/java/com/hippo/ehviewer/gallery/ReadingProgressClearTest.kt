package com.hippo.ehviewer.gallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [GalleryProvider2.clearReadingProgress] must remove BOTH the page and the
 * timestamp key (a surviving `_ts` key would make the tracker treat the SP
 * default of page 0 as real local progress) and push the no-progress
 * sentinel into [ReadingProgressTracker] so detail-page observers refresh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ReadingProgressClearTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `clear removes page and timestamp and resets the tracker flow`() {
        GalleryProvider2.saveReadingProgress(context, "arc-clear", 12)
        assertEquals(12, GalleryProvider2.loadReadingProgress(context, "arc-clear"))

        GalleryProvider2.clearReadingProgress(context, "arc-clear")

        assertEquals(0, GalleryProvider2.loadReadingProgress(context, "arc-clear"))
        assertEquals(0L, GalleryProvider2.loadReadingTimestamp(context, "arc-clear"))
        assertEquals(
            ReadingProgressTracker.NO_LOCAL_PROGRESS,
            ReadingProgressTracker.progressFlow("arc-clear").value,
        )
    }

    @Test
    fun `clear for an arcid with no saved progress is a no-op`() {
        GalleryProvider2.clearReadingProgress(context, "arc-never")
        assertEquals(0, GalleryProvider2.loadReadingProgress(context, "arc-never"))
    }
}
