package com.hippo.ehviewer.ui.scene.gallery.detail

import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryInfo
import org.junit.Assert.assertEquals
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
    fun secondNavigation_afterReset_returnsNewGalleryGid_notStaleDetail() {
        val vm = GalleryDetailViewModel()

        val galleryA = GalleryInfo().apply { gid = 100L; token = "tokA" }
        val detailA = GalleryDetail().apply { gid = 100L; token = "tokA" }
        val galleryB = GalleryInfo().apply { gid = 200L; token = "tokB" }

        // First navigation: A loaded with both info and detail.
        vm.setGalleryInfo(galleryA)
        vm.setGalleryDetail(detailA)
        assertEquals(100L, vm.getEffectiveGid())
        assertEquals("tokA", vm.getEffectiveToken())
        assertSame(detailA, vm.getEffectiveGalleryInfo())

        // User pops back and clicks gallery B. The Scene must reset the
        // ViewModel before writing the new arguments, otherwise the stale
        // detail from gallery A wins via the detail > info fallback.
        vm.resetForNewEntry()
        vm.setGalleryInfo(galleryB)

        assertEquals(200L, vm.getEffectiveGid())
        assertEquals("tokB", vm.getEffectiveToken())
        assertSame(galleryB, vm.getEffectiveGalleryInfo())
    }

    @Test
    fun resetForNewEntry_clearsAllPerEntryState() {
        val vm = GalleryDetailViewModel()

        vm.setAction(GalleryDetailScene.ACTION_GALLERY_INFO)
        vm.setGid(42L)
        vm.setToken("tok")
        vm.setGalleryInfo(GalleryInfo().apply { gid = 42L })
        vm.setGalleryDetail(GalleryDetail().apply { gid = 42L })
        vm.setState(GalleryDetailViewModel.STATE_NORMAL)

        vm.resetForNewEntry()

        assertNull(vm.action.value)
        assertEquals(0L, vm.gid.value)
        assertNull(vm.token.value)
        assertNull(vm.galleryInfo.value)
        assertNull(vm.galleryDetail.value)
        assertNull(vm.downloadInfo.value)
        assertEquals(GalleryDetailViewModel.STATE_INIT, vm.state.value)
        assertEquals(-1L, vm.getEffectiveGid())
        assertNull(vm.getEffectiveGalleryInfo())
    }

    @Test
    fun secondNavigation_withDownloadInfo_doesNotLeakIntoFreshGalleryInfo() {
        val vm = GalleryDetailViewModel()

        // First entry: opened from downloads scene with a DownloadInfo.
        val downloadDetail = GalleryDetail().apply { gid = 555L; token = "downTok" }
        vm.setGalleryInfo(downloadDetail)
        vm.setGalleryDetail(downloadDetail)
        assertEquals(555L, vm.getEffectiveGid())

        // Second entry: search-result click on a different gallery.
        vm.resetForNewEntry()
        val freshInfo = GalleryInfo().apply { gid = 777L; token = "freshTok" }
        vm.setGalleryInfo(freshInfo)

        assertEquals(777L, vm.getEffectiveGid())
        assertNull(vm.downloadInfo.value)
    }
}
