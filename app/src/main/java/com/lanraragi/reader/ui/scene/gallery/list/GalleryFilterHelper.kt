package com.lanraragi.reader.ui.scene.gallery.list

import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.lanraragi.reader.R

/**
 * Draws the multi-tag filter FAB for GalleryListScene. The filter itself is
 * the screen's [GalleryTagFilter], saved with it.
 */
class GalleryFilterHelper(
    private val filter: GalleryTagFilter,
    private val callback: Callback,
) {

    interface Callback {
        fun getFilterFab(): FloatingActionButton?
    }

    val filterOpen: Boolean get() = filter.isOpen
    val filterTagList: List<String> get() = filter.tags

    fun toggleFilter() {
        filter.toggle()
        updateFilterIcon()
    }

    /** Shows the filter's state on its FAB: closed, or open with its tag count. */
    fun updateFilterIcon() {
        val fab = callback.getFilterFab() ?: return
        if (!filter.isOpen) {
            fab.setImageResource(R.drawable.ic_baseline_filter_none_24)
            return
        }

        val resId = when (filter.tags.size) {
            0 -> R.drawable.ic_baseline_filter_24
            1 -> R.drawable.ic_baseline_filter_1_24
            2 -> R.drawable.ic_baseline_filter_2_24
            3 -> R.drawable.ic_baseline_filter_3_24
            4 -> R.drawable.ic_baseline_filter_4_24
            5 -> R.drawable.ic_baseline_filter_5_24
            6 -> R.drawable.ic_baseline_filter_6_24
            7 -> R.drawable.ic_baseline_filter_7_24
            8 -> R.drawable.ic_baseline_filter_8_24
            9 -> R.drawable.ic_baseline_filter_9_24
            else -> R.drawable.ic_baseline_filter_9_plus_24
        }
        fab.setImageResource(resId)
    }

    /** Adds a tag (namespace stripped) to the filter; @return the filter's search text */
    fun searchTagBuild(tagName: String): String = filter.add(tagName)

    /** Removes the last tag; false if 0 or 1 tag remains. */
    fun removeLastFilterTag(): Boolean = filter.dropLast()

    /** The filter's search text. */
    fun searchText(): String = filter.searchText()
}
