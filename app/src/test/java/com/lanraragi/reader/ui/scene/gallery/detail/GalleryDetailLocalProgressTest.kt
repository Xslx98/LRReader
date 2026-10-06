package com.lanraragi.reader.ui.scene.gallery.detail

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.gallery.GalleryProvider2
import com.lanraragi.reader.gallery.LocalReadingProgress
import com.lanraragi.reader.gallery.ReadingProgressTracker
import com.lanraragi.reader.module.CoroutineModule
import com.lanraragi.reader.stubAppModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit 2026-10-06c C37 note: the reader saves a download's position under
 * the archive's source profile, so the detail header must observe that
 * profile's progress, not the active one's. Profile 1 is active throughout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class GalleryDetailLocalProgressTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val collectors = mutableListOf<Job>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        ServiceRegistry.initializeForTest(CoroutineModule(), app = stubAppModule(ctx))
        LocalReadingProgress.resetForTest()
        LocalReadingProgress.profileId = { 1L }
        LocalReadingProgress.prefs(ctx).edit().clear().commit()
    }

    @After
    fun tearDown() {
        collectors.forEach { it.cancel() }
        LocalReadingProgress.resetForTest()
        LocalReadingProgress.prefs(ctx).edit().clear().commit()
        Dispatchers.resetMain()
    }

    private fun archive(arcid: String, serverProfileId: Long) = Archive(
        arcid = arcid,
        title = "title-$arcid",
        tags = emptyMap(),
        pagecount = 50,
        progress = 0,
        extension = "",
        filename = "",
        thumbnailUrl = "",
        rating = 0f,
        isnew = false,
        lastreadtime = 0L,
        summary = null,
        serverProfileId = serverProfileId,
    )

    /** A detail page shown for [archive], with the header flow collected like the Scene does. */
    private fun header(archive: Archive): GalleryDetailViewModel {
        val vm = GalleryDetailViewModel()
        vm.setArcid(archive.arcid)
        vm.setArchive(archive)
        collectors += CoroutineScope(Dispatchers.Unconfined).launch { vm.localReadingPage.collect {} }
        return vm
    }

    @Test
    fun `a page read on another server's download shows in its header`() {
        val vm = header(archive("arc-hdr-b", serverProfileId = 2))
        assertEquals(ReadingProgressTracker.NO_LOCAL_PROGRESS, vm.localReadingPage.value)

        // What DirGalleryProvider does for a download of profile 2.
        GalleryProvider2.saveReadingProgress(ctx, "arc-hdr-b", 17, profileId = 2)

        assertEquals(17, vm.localReadingPage.value)
    }

    @Test
    fun `a header opened after the read starts at the source profile's page`() {
        GalleryProvider2.saveReadingProgress(ctx, "arc-hdr-reopen", 8, profileId = 2)

        assertEquals(8, header(archive("arc-hdr-reopen", serverProfileId = 2)).localReadingPage.value)
    }

    @Test
    fun `an active profile archive and a legacy row keep the active profile's page`() {
        val own = header(archive("arc-hdr-own", serverProfileId = 1))
        val legacy = header(archive("arc-hdr-legacy", serverProfileId = 0))

        GalleryProvider2.saveReadingProgress(ctx, "arc-hdr-own", 5, profileId = 1)
        GalleryProvider2.saveReadingProgress(ctx, "arc-hdr-legacy", 6, profileId = 1)
        GalleryProvider2.saveReadingProgress(ctx, "arc-hdr-own", 40, profileId = 2)

        assertEquals(5, own.localReadingPage.value)
        assertEquals(6, legacy.localReadingPage.value)
    }

    @Test
    fun `a position an older build saved under the active profile is shown until the first source save`() {
        // Pre-2026-10-06b builds saved every archive under the active profile;
        // the reader resumes from that entry while the source key is empty.
        LocalReadingProgress.save(ctx, "arc-hdr-old", 30, nowSeconds = 100, profileId = 1)

        val vm = header(archive("arc-hdr-old", serverProfileId = 2))
        assertEquals(30, vm.localReadingPage.value)

        GalleryProvider2.saveReadingProgress(ctx, "arc-hdr-old", 31, profileId = 2)
        assertEquals(31, vm.localReadingPage.value)
    }
}
