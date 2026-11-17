package com.lanraragi.reader.ui.scene.download

/**
 * The Downloads list's search and filter, one value owned by
 * [DownloadsViewModel] (it replaces the searching / searchKey /
 * filterLoading flags and the filter-done event).
 *
 * @property query the search, applied to every list the label publishes;
 *   null shows the whole label
 * @property order the state filter or sort picked from the menu (its menu
 *   item id), applied after the search to every list the label publishes;
 *   null keeps the label's own order
 * @property applying a pass the user started runs (the list spins and
 *   switching label waits for it)
 */
data class DownloadsFilter(
    val query: String? = null,
    val order: Int? = null,
    val applying: Boolean = false,
)
