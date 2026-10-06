package com.lanraragi.reader.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.gallery.LocalReadingProgress
import com.lanraragi.reader.gallery.ReadingProgressTracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit 2026-10-06 C37: "Reset reading progress" clears the local position of
 * each download's own source profile, not only the active profile's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ProgressResetLocalTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var active = 1L

    @Before
    fun setUp() {
        LocalReadingProgress.resetForTest()
        LocalReadingProgress.profileId = { active }
        LocalReadingProgress.prefs(ctx).edit().clear().commit()
    }

    @After
    fun tearDown() {
        LocalReadingProgress.resetForTest()
        LocalReadingProgress.prefs(ctx).edit().clear().commit()
    }

    private fun saveFor(profile: Long, arcid: String, page: Int) {
        LocalReadingProgress.profileId = { profile }
        LocalReadingProgress.save(ctx, arcid, page, nowSeconds = 100)
        LocalReadingProgress.profileId = { active }
    }

    @Test
    fun `a download from another server has its own position cleared`() {
        saveFor(2, "arc-other", 12)
        saveFor(1, "arc-other", 7)

        DownloadManager.clearLocalProgress(ctx, "arc-other", sourceProfileId = 2)

        val keys = LocalReadingProgress.prefs(ctx).all.keys
        assertEquals("only the active profile's entry stays", setOf("1:arc-other", "1:arc-other_ts"), keys)
    }

    @Test
    fun `an active profile download and a legacy row clear the active position`() {
        saveFor(1, "arc-active", 5)
        DownloadManager.clearLocalProgress(ctx, "arc-active", sourceProfileId = 1)
        saveFor(1, "arc-legacy", 6)
        DownloadManager.clearLocalProgress(ctx, "arc-legacy", sourceProfileId = 0)

        assertEquals(emptySet<String>(), LocalReadingProgress.prefs(ctx).all.keys)
        assertEquals(ReadingProgressTracker.NO_LOCAL_PROGRESS, ReadingProgressTracker.progressFlow("arc-active").value)
    }

    @Test
    fun `a tracker flow created while the other server was active is reset too`() {
        active = 2
        LocalReadingProgress.profileId = { active }
        ReadingProgressTracker.setProgress("arc-flow", 9)
        active = 1
        LocalReadingProgress.profileId = { active }

        DownloadManager.clearLocalProgress(ctx, "arc-flow", sourceProfileId = 2)

        active = 2
        LocalReadingProgress.profileId = { active }
        assertEquals(ReadingProgressTracker.NO_LOCAL_PROGRESS, ReadingProgressTracker.progressFlow("arc-flow").value)
    }
}
