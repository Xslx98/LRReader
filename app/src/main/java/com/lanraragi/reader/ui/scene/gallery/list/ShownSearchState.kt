package com.lanraragi.reader.ui.scene.gallery.list

import android.os.Bundle
import androidx.core.os.BundleCompat

/**
 * The `/api/search` inputs behind the rows ONE list scene shows, for the
 * "continue to the next archive" reading context (audit 2026-10-06 C01).
 *
 * [GalleryListViewModel] is activity-scoped, so every list scene on the back
 * stack shares it: a tag search opened from a detail page used to overwrite the
 * query of the list underneath, and opening an archive after popping back
 * continued through the tag search's results. Each scene now owns one of these,
 * fed only by its own [GalleryListDataHelper], and saves it with its state — a
 * restored scene that does not reload must still know what it shows.
 */
internal class ShownSearchState {

    /** The query of the last load that landed in this scene, or null before any. */
    var params: GalleryListViewModel.SearchParams? = null
        private set

    fun onLoaded(params: GalleryListViewModel.SearchParams) {
        this.params = params
    }

    /** Before any load lands the context carries the unfiltered default query. */
    fun forReadingContext(): GalleryListViewModel.SearchParams =
        params ?: GalleryListViewModel.SearchParams()

    fun save(outState: Bundle) {
        outState.putParcelable(KEY_SHOWN_SEARCH, params)
    }

    fun restore(savedInstanceState: Bundle) {
        params = BundleCompat.getParcelable(
            savedInstanceState, KEY_SHOWN_SEARCH, GalleryListViewModel.SearchParams::class.java
        )
    }

    companion object {
        const val KEY_SHOWN_SEARCH = "shown_search"
    }
}
