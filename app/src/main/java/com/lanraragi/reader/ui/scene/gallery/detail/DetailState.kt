package com.lanraragi.reader.ui.scene.gallery.detail

/**
 * What the detail page shows, owned by [GalleryDetailViewModel]; the scene
 * renders it. A StateFlow of this replaces the scene's STATE_* ints and the
 * one-shot load / failure events: a result that lands before the view
 * subscribes is simply the current state.
 */
sealed interface DetailState {

    /** Nothing opened yet. */
    data object Init : DetailState

    /** A request runs and there is nothing to show: full-page spinner. */
    data object Loading : DetailState

    /** A request runs; the header from the navigation archive shows, the rest spins. */
    data object LoadingBelowHeader : DetailState

    /**
     * The detail is shown. [sourceError] is the failure of the last request
     * when the page shows cached or navigation data instead (a banner over it).
     * [load] counts the fetches that succeeded, so a refresh that returns the
     * same content is still a new state (the page rebinds it).
     */
    data class Shown(val sourceError: Exception? = null, val load: Int = 0) : DetailState

    /**
     * Nothing to show. [error] is the request's failure; null when the page
     * has no archive id to ask for.
     */
    data class Failed(val error: Exception?) : DetailState

    val isLoading: Boolean get() = this == Loading || this == LoadingBelowHeader
}
