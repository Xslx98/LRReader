package com.hippo.ehviewer.ui.scene.gallery.detail

import com.hippo.ehviewer.gallery.ReadingProgressTracker
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.ArchiveDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the Activity-scoped state bleed in [GalleryDetailViewModel].
 *
 * Background: GalleryDetailViewModel is scoped via
 * `ViewModelProvider(requireActivity())`, so the same instance is reused
 * across navigations. The `getEffective*()` accessors prefer archiveDetail
 * over the navigation Archive (the "archiveDetail > archive > args" fallback).
 * When the user pops back to the list and clicks a different gallery,
 * [setArchive] writes the new arg but the previous gallery's
 * `_archiveDetail` is still cached. Without an explicit reset every
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

    private fun archiveDetail(arcid: String) = ArchiveDetail(
        archive = archive(arcid),
        tagGroups = emptyList(),
        language = null,
        size = null,
    )

    @Test
    fun secondNavigation_afterReset_returnsNewGalleryArcid_notStaleDetail() {
        val vm = GalleryDetailViewModel()

        val archiveB = archive("tokB")

        // First navigation: A loaded with both archive and detail.
        vm.setArchive(archive("tokA"))
        vm.setArchiveDetail(archiveDetail("tokA"))
        assertEquals("tokA", vm.getEffectiveArcid())

        // User pops back and clicks gallery B. The Scene must reset the
        // ViewModel before writing the new arguments, otherwise the stale
        // detail from gallery A wins via the archiveDetail > archive fallback.
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
        vm.setArchiveDetail(archiveDetail("tok"))
        vm.updateFavoriteState(FavoriteState(isFavorited = true, name = "slot"))
        vm.updateCurrentRating(4f)
        vm.setState(GalleryDetailViewModel.STATE_NORMAL)

        vm.resetForNewEntry()

        assertNull(vm.action.value)
        assertNull(vm.arcid.value)
        assertNull(vm.archive.value)
        assertNull(vm.archiveDetail.value)
        assertNull(vm.favoriteState.value)
        assertNull(vm.currentRating.value)
        assertEquals(GalleryDetailViewModel.STATE_INIT, vm.state.value)
        assertNull(vm.getEffectiveArcid())
        assertNull(vm.getEffectiveArchive())
    }

    @Test
    fun updateFavoriteState_storesAndExposesViaFlow() {
        val vm = GalleryDetailViewModel()
        assertNull(vm.favoriteState.value)

        vm.updateFavoriteState(FavoriteState(isFavorited = true, name = "manga"))
        assertEquals(true, vm.favoriteState.value?.isFavorited)
        assertEquals("manga", vm.favoriteState.value?.name)

        vm.updateFavoriteState(FavoriteState(isFavorited = false, name = null))
        assertEquals(false, vm.favoriteState.value?.isFavorited)
        assertNull(vm.favoriteState.value?.name)

        vm.updateFavoriteState(null)
        assertNull(vm.favoriteState.value)
    }

    @Test
    fun updateCurrentRating_storesAndExposesViaFlow() {
        val vm = GalleryDetailViewModel()
        assertNull(vm.currentRating.value)

        vm.updateCurrentRating(3f)
        assertEquals(3f, vm.currentRating.value)

        vm.updateCurrentRating(5f)
        assertEquals(5f, vm.currentRating.value)
    }

    @Test
    fun setArchiveDetail_initializesCurrentRatingFromArchive() {
        val vm = GalleryDetailViewModel()

        // setArchiveDetail seeds _currentRating from archive.rating so the
        // detail page never displays a missing rating between load and the
        // first user touch.
        val ad = archiveDetail("tok").copy(
            archive = archive("tok").copy(rating = 4.5f)
        )
        vm.setArchiveDetail(ad)
        assertEquals(4.5f, vm.currentRating.value)

        // Clearing falls back to null.
        vm.setArchiveDetail(null)
        assertNull(vm.currentRating.value)
    }

    @Test
    fun secondNavigation_doesNotLeakDetailIntoFreshArchive() {
        val vm = GalleryDetailViewModel()

        // First entry: opened from downloads scene — detail loaded.
        vm.setArchive(archive("downTok"))
        vm.setArchiveDetail(archiveDetail("downTok"))
        assertEquals("downTok", vm.getEffectiveArcid())

        // Second entry: search-result click on a different gallery.
        vm.resetForNewEntry()
        vm.setArchive(archive("freshTok"))

        assertEquals("freshTok", vm.getEffectiveArcid())
        assertNull(vm.archiveDetail.value)
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
