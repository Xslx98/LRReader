package com.hippo.ehviewer.ui.scene.gallery.detail

import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.gallery.ReadingProgressTracker
import com.lanraragi.reader.domain.Archive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the Activity-scoped state bleed in [GalleryDetailViewModel].
 *
 * Background: GalleryDetailViewModel is scoped via
 * `ViewModelProvider(requireActivity())`, so the same instance is reused
 * across navigations. The `getEffective*()` accessors prefer galleryDetail
 * over the navigation Archive (the "detail > archive > args" fallback).
 * When the user pops back to the list and clicks a different gallery,
 * [setArchive] writes the new arg but the previous gallery's
 * `_galleryDetail` is still cached. Without an explicit reset every
 * getEffective*() call returns the stale arcid → the new detail screen
 * renders the old gallery, downloads it, etc.
 *
 * Fix: [GalleryDetailViewModel.resetForNewEntry] clears all per-entry state
 * and must be called by the Scene's `handleArgs()` before writing the new
 * arguments.
 */
class GalleryDetailViewModelTest {

    private fun archive(arcid: String) = Archive(
        arcid = arcid,
        title = "title-$arcid",
        tags = emptyMap(),
        pagecount = 0,
        progress = 0,
        extension = "",
        filename = "",
        thumbnailUrl = "",
        rating = 0f,
        isnew = false,
        lastreadtime = 0L,
        summary = null,
        serverProfileId = 0L,
    )

    @Test
    fun secondNavigation_afterReset_returnsNewGalleryArcid_notStaleDetail() {
        val vm = GalleryDetailViewModel()

        val archiveA = archive("tokA")
        val detailA = GalleryDetail().apply { arcid = "tokA" }
        val archiveB = archive("tokB")

        // First navigation: A loaded with both archive and detail.
        vm.setArchive(archiveA)
        vm.setGalleryDetail(detailA)
        assertEquals("tokA", vm.getEffectiveArcid())

        // User pops back and clicks gallery B. The Scene must reset the
        // ViewModel before writing the new arguments, otherwise the stale
        // detail from gallery A wins via the detail > archive fallback.
        vm.resetForNewEntry()
        vm.setArchive(archiveB)

        assertEquals("tokB", vm.getEffectiveArcid())
        assertEquals(archiveB, vm.getEffectiveArchive())
    }

    @Test
    fun resetForNewEntry_clearsAllPerEntryState() {
        val vm = GalleryDetailViewModel()

        vm.setAction(GalleryDetailScene.ACTION_ARCHIVE)
        vm.setArcid("tok")
        vm.setArchive(archive("tok"))
        vm.setGalleryDetail(GalleryDetail().apply { arcid = "tok" })
        vm.setState(GalleryDetailViewModel.STATE_NORMAL)

        vm.resetForNewEntry()

        assertNull(vm.action.value)
        assertNull(vm.arcid.value)
        assertNull(vm.archive.value)
        assertNull(vm.galleryDetail.value)
        assertNull(vm.downloadInfo.value)
        assertEquals(GalleryDetailViewModel.STATE_INIT, vm.state.value)
        assertNull(vm.getEffectiveArcid())
        assertNull(vm.getEffectiveArchive())
    }

    @Test
    fun secondNavigation_doesNotLeakDownloadInfoIntoFreshArchive() {
        val vm = GalleryDetailViewModel()

        // First entry: opened from downloads scene — downloadInfo set.
        val downloadDetail = GalleryDetail().apply { arcid = "downTok" }
        vm.setArchive(archive("downTok"))
        vm.setGalleryDetail(downloadDetail)
        assertEquals("downTok", vm.getEffectiveArcid())

        // Second entry: search-result click on a different gallery.
        vm.resetForNewEntry()
        vm.setArchive(archive("freshTok"))

        assertEquals("freshTok", vm.getEffectiveArcid())
        assertNull(vm.downloadInfo.value)
    }

    /**
     * Regression: constructing the VM must not eagerly start [GalleryDetailViewModel.localReadingPage]
     * — that flow lazily reads SharedPreferences via ServiceRegistry, which is uninitialized
     * in unit tests and would surface as kotlinx.coroutines.test's
     * `UncaughtExceptionsBeforeTest` against an unrelated test class.
     *
     * See commit history for the original eager `SharingStarted.Eagerly` flake.
     */
    @Test
    fun construction_doesNotTriggerServiceRegistry() {
        val vm = GalleryDetailViewModel()
        // StateFlow exists, holds the sentinel, and was created without throwing.
        assertNotNull(vm.localReadingPage)
        assertEquals(ReadingProgressTracker.NO_LOCAL_PROGRESS, vm.localReadingPage.value)
    }
}
