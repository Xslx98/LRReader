package com.hippo.ehviewer.gallery

import com.lanraragi.reader.domain.Archive

/**
 * Where the user was reading FROM when they opened the current archive.
 * Captured by list UIs at item-click time (see [ReadingContextStore]) and
 * consumed by NextArchiveResolver to find "the next archive in that list".
 */
sealed interface ReadingContext {

    /** arcid of the archive the user opened from this context. */
    val anchorArcid: String

    /**
     * Online browse / search / category browsing (they share one pipeline —
     * a category is just a search with a `category` param). The source
     * profile/baseUrl are pinned at capture time so resolution keeps working
     * after a profile switch (multi-profile red line: explicit source).
     */
    data class OnlineSearch(
        val sourceProfileId: Long,
        val sourceBaseUrl: String,
        val filter: String?,
        val category: String?,
        val sortby: String?,
        val order: String?,
        val newonly: Boolean,
        val untaggedonly: Boolean,
        override val anchorArcid: String,
        /** Absolute position in the result set at capture time (best effort). */
        val anchorIndex: Int,
    ) : ReadingContext

    /**
     * A local, fully-materialised list (downloads / history). Holds a
     * forward-trimmed snapshot of the UI order taken at click time: history
     * ordering self-mutates when reading (HISTORY_TIME desc), so re-querying
     * Room at resolve time would destroy the anchor's neighbourhood.
     * Snapshot is capped at [ReadingContextStore.LOCAL_WINDOW] entries.
     */
    data class LocalList(
        val kind: Kind,
        /** anchor itself at index 0, then the following entries in UI order. */
        val forwardArchives: List<Archive>,
        override val anchorArcid: String,
    ) : ReadingContext {
        enum class Kind { DOWNLOADS, HISTORY }
    }
}
