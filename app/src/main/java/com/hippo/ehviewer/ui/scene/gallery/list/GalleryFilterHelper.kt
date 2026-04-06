package com.hippo.ehviewer.ui.scene.gallery.list

import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.hippo.ehviewer.R

/**
 * Manages multi-tag filter state and FAB icon for GalleryListScene.
 * Extracted to reduce GalleryListScene's line count.
 */
class GalleryFilterHelper(private val callback: Callback) {

    interface Callback {
        fun getFilterFab(): FloatingActionButton?
    }

    var filterOpen = false
        private set
    val filterTagList: MutableList<String> = ArrayList()

    fun toggleFilter() {
        filterOpen = !filterOpen
        updateFilterIcon(filterTagList.size)
    }

    /**
     * Update filter FAB icon based on open state and tag count.
     * When closing, also clears the tag list.
     */
    fun updateFilterIcon(num: Int) {
        val fab = callback.getFilterFab() ?: return
        if (!filterOpen) {
            fab.setImageResource(R.drawable.ic_baseline_filter_none_24)
            filterTagList.clear()
            return
        }

        val resId = when (num) {
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

    /**
     * Add a tag to the filter list and return the joined filter string.
     * Strips namespace prefix before adding.
     */
    fun searchTagBuild(tagName: String): String {
        val list = tagName.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val key = if (list.size == 2) list[1] else list[0]

        if (!filterTagList.contains(key)) {
            filterTagList.add(key)
        }
        return listToString(filterTagList)
    }

    /**
     * Remove the last tag from the filter list.
     * @return true if a tag was removed, false if only 0 or 1 tag remains.
     */
    fun removeLastFilterTag(): Boolean {
        if (filterTagList.size > 1) {
            filterTagList.removeAt(filterTagList.size - 1)
            return true
        }
        return false
    }

    fun listToString(list: List<String>): String {
        val result = StringBuilder()
        for (i in list.indices) {
            if (i == 0) {
                result.append(list[i])
            } else {
                result.append("  ").append(list[i])
            }
        }
        return result.toString()
    }
}
