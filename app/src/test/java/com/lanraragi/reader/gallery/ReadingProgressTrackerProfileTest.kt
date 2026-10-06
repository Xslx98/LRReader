package com.lanraragi.reader.gallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit 2026-10-06 C37: the detail header's local page must not follow the
 * same arcid across a server profile switch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ReadingProgressTrackerProfileTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var profile = 1L

    @Before
    fun setUp() {
        LocalReadingProgress.resetForTest()
        LocalReadingProgress.profileId = { profile }
        LocalReadingProgress.prefs(ctx).edit().clear().commit()
    }

    @After
    fun tearDown() {
        LocalReadingProgress.resetForTest()
        LocalReadingProgress.prefs(ctx).edit().clear().commit()
    }

    @Test
    fun `a page read on one server is not shown for the same arcid on another`() {
        GalleryProvider2.saveReadingProgress(ctx, "arc-two-servers", 30)
        assertEquals(30, ReadingProgressTracker.progressFlow("arc-two-servers").value)

        profile = 2

        assertEquals(
            ReadingProgressTracker.NO_LOCAL_PROGRESS,
            ReadingProgressTracker.progressFlow("arc-two-servers").value,
        )
        GalleryProvider2.saveReadingProgress(ctx, "arc-two-servers", 4)
        assertEquals(4, ReadingProgressTracker.progressFlow("arc-two-servers").value)

        profile = 1
        assertEquals(30, ReadingProgressTracker.progressFlow("arc-two-servers").value)
    }
}
