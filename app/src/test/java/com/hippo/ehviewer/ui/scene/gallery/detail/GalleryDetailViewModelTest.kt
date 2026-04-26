package com.hippo.ehviewer.ui.scene.gallery.detail

import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.gallery.ReadingProgressTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Regression tests for the Activity-scoped state bleed in [GalleryDetailViewModel].
 *
 * Background: GalleryDetailViewModel is scoped via
 * `ViewModelProvider(requireActivity())`, so the same instance is reused
 * across navigations. The `getEffective*()` accessors prefer galleryDetail
 * over galleryInfo (the "detail > info > args" fallback). When the user
 * pops back to the list and clicks a different gallery, [setGalleryInfo]
 * writes the new info but the previous gallery's `_galleryDetail` is still
 * cached. Without an explicit reset every getEffective*() call returns the
 * stale gid → the new detail screen renders the old gallery, downloads it,
 * etc. The reader path is unaffected because it goes through an Intent
 * with the GalleryInfo embedded directly, bypassing the ViewModel.
 *
 * Fix: [GalleryDetailViewModel.resetForNewEntry] clears all per-entry state
 * and must be called by the Scene's `handleArgs()` before writing the new
 * arguments.
 */
class GalleryDetailViewModelTest {

    @Test
    fun secondNavigation_afterReset_returnsNewGalleryArcid_notStaleDetail() {
        val vm = GalleryDetailViewModel()

        val galleryA = GalleryInfo().apply { arcid = "tokA" }
        val detailA = GalleryDetail().apply { arcid = "tokA" }
        val galleryB = GalleryInfo().apply { arcid = "tokB" }

        // First navigation: A loaded with both info and detail.
        vm.setGalleryInfo(galleryA)
        vm.setGalleryDetail(detailA)
        assertEquals("tokA", vm.getEffectiveArcid())
        assertSame(detailA, vm.getEffectiveGalleryInfo())

        // User pops back and clicks gallery B. The Scene must reset the
        // ViewModel before writing the new arguments, otherwise the stale
        // detail from gallery A wins via the detail > info fallback.
        vm.resetForNewEntry()
        vm.setGalleryInfo(galleryB)

        assertEquals("tokB", vm.getEffectiveArcid())
        assertSame(galleryB, vm.getEffectiveGalleryInfo())
    }

    @Test
    fun resetForNewEntry_clearsAllPerEntryState() {
        val vm = GalleryDetailViewModel()

        vm.setAction(GalleryDetailScene.ACTION_ARCHIVE)
        vm.setGid(42L)
        vm.setArcid("tok")
        vm.setGalleryInfo(GalleryInfo().apply { arcid = "tok" })
        vm.setGalleryDetail(GalleryDetail().apply { arcid = "tok" })
        vm.setState(GalleryDetailViewModel.STATE_NORMAL)

        vm.resetForNewEntry()

        assertNull(vm.action.value)
        assertEquals(0L, vm.gid.value)
        assertNull(vm.arcid.value)
        assertNull(vm.galleryInfo.value)
        assertNull(vm.galleryDetail.value)
        assertNull(vm.downloadInfo.value)
        assertEquals(GalleryDetailViewModel.STATE_INIT, vm.state.value)
        assertNull(vm.getEffectiveArcid())
        assertNull(vm.getEffectiveGalleryInfo())
    }

    @Test
    fun secondNavigation_withDownloadInfo_doesNotLeakIntoFreshGalleryInfo() {
        val vm = GalleryDetailViewModel()

        // First entry: opened from downloads scene with a DownloadInfo.
        val downloadDetail = GalleryDetail().apply { arcid = "downTok" }
        vm.setGalleryInfo(downloadDetail)
        vm.setGalleryDetail(downloadDetail)
        assertEquals("downTok", vm.getEffectiveArcid())

        // Second entry: search-result click on a different gallery.
        vm.resetForNewEntry()
        val freshInfo = GalleryInfo().apply { arcid = "freshTok" }
        vm.setGalleryInfo(freshInfo)

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
