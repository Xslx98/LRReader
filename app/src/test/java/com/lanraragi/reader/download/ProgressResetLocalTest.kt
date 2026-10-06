package com.lanraragi.reader.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.framework.unifile.UniFile
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.gallery.DirGalleryProvider
import com.lanraragi.reader.gallery.LocalReadingProgress
import com.lanraragi.reader.gallery.ReadingProgressTracker
import com.lanraragi.reader.module.CoroutineModule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit 2026-10-06 C37 / 2026-10-06b: the reader keys a download's local
 * position by the download's source profile, and "Reset reading progress"
 * leaves no entry the reader would resume from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ProgressResetLocalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var active = 1L

    @Before
    fun setUp() {
        ServiceRegistry.initializeForTest(CoroutineModule())
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

    /** Opens [arcid] from the Downloads list the way GalleryActivity does (no server snapshot). */
    private fun openDownload(arcid: String, sourceProfileId: Long): DirGalleryProvider =
        DirGalleryProvider(UniFile.fromFile(tmp.root)!!, ctx, arcid, sourceProfileId)

    private fun keys(): Set<String> = LocalReadingProgress.prefs(ctx).all.keys

    @Test
    fun `reading another server's download stores the position under that server`() {
        openDownload("arc-b", sourceProfileId = 2).putStartPage(30)

        assertEquals(setOf("2:arc-b", "2:arc-b_ts"), keys())
        assertEquals(30, openDownload("arc-b", sourceProfileId = 2).getStartPage())
    }

    @Test
    fun `reset of another server's download read while this profile is active reopens at the start`() {
        openDownload("arc-b", sourceProfileId = 2).putStartPage(30)

        DownloadManager.clearLocalProgress(ctx, "arc-b", sourceProfileId = 2)

        assertEquals(0, openDownload("arc-b", sourceProfileId = 2).getStartPage())
    }

    @Test
    fun `a position saved under the active profile by an older build is still found`() {
        saveFor(1, "arc-b", 30)

        val reopened = openDownload("arc-b", sourceProfileId = 2)

        assertEquals(30, reopened.getStartPage())
        reopened.putStartPage(31)
        assertEquals("the next save goes to the source key", 31, LocalReadingProgress.prefs(ctx).getInt("2:arc-b", -1))
        assertEquals(31, openDownload("arc-b", sourceProfileId = 2).getStartPage())
    }

    @Test
    fun `the source profile's own entry wins over the active profile's`() {
        saveFor(1, "arc-mirror", 4)
        saveFor(2, "arc-mirror", 9)

        assertEquals(9, openDownload("arc-mirror", sourceProfileId = 2).getStartPage())
        assertEquals(4, openDownload("arc-mirror", sourceProfileId = 1).getStartPage())
    }

    @Test
    fun `reset after an older build saved under the active profile reopens at the start`() {
        saveFor(1, "arc-b", 30)

        DownloadManager.clearLocalProgress(ctx, "arc-b", sourceProfileId = 2)

        assertFalse("no entry is left to fall back to", keys().any { it.contains("arc-b") })
        assertEquals(0, openDownload("arc-b", sourceProfileId = 2).getStartPage())
    }

    @Test
    fun `reset clears both the source and the active profile's entry`() {
        saveFor(2, "arc-other", 12)
        saveFor(1, "arc-other", 7)
        saveFor(3, "arc-other", 5)

        DownloadManager.clearLocalProgress(ctx, "arc-other", sourceProfileId = 2)

        assertEquals("an unrelated profile keeps its entry", setOf("3:arc-other", "3:arc-other_ts"), keys())
    }

    @Test
    fun `an active profile download and a legacy row clear the active position`() {
        saveFor(1, "arc-active", 5)
        DownloadManager.clearLocalProgress(ctx, "arc-active", sourceProfileId = 1)
        saveFor(1, "arc-legacy", 6)
        DownloadManager.clearLocalProgress(ctx, "arc-legacy", sourceProfileId = 0)

        assertEquals(emptySet<String>(), keys())
        assertEquals(ReadingProgressTracker.NO_LOCAL_PROGRESS, ReadingProgressTracker.progressFlow("arc-active").value)
    }

    @Test
    fun `a legacy row is read and saved under the active profile`() {
        openDownload("arc-legacy", sourceProfileId = 0).putStartPage(3)

        assertTrue(LocalReadingProgress.prefs(ctx).contains("1:arc-legacy"))
        assertEquals(3, openDownload("arc-legacy", sourceProfileId = 0).getStartPage())
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

    @Test
    fun `a page read on another server's download updates that server's tracker flow`() {
        openDownload("arc-t", sourceProfileId = 2).putStartPage(6)

        // The detail header observes the source profile's flow (audit 2026-10-06c C37 note).
        assertEquals(6, ReadingProgressTracker.progressFlow(2, "arc-t").value)
        assertEquals(
            "the active profile's flow is untouched",
            ReadingProgressTracker.NO_LOCAL_PROGRESS,
            ReadingProgressTracker.progressFlow("arc-t").value,
        )
    }
}
