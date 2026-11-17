package com.lanraragi.reader.ui.scene.gallery.list

import androidx.lifecycle.SavedStateHandle
import com.lanraragi.reader.domain.splitNamespace

/**
 * The list's multi-tag filter (first entry of the FAB menu): whether it is
 * open, and the tags picked so far (namespace stripped, in order). Saved
 * with the screen, so a rotation or a process-death restore keeps it next
 * to the filtered list the url builder restores; it used to live in a
 * helper rebuilt with each view and was lost.
 */
class GalleryTagFilter(private val saved: SavedStateHandle = SavedStateHandle()) {

    val isOpen: Boolean get() = saved[KEY_OPEN] ?: false

    val tags: List<String> get() = saved.get<ArrayList<String>>(KEY_TAGS).orEmpty()

    /** Opens the filter, or closes it and forgets its tags. */
    fun toggle() {
        if (isOpen) {
            saved[KEY_OPEN] = false
            saved[KEY_TAGS] = ArrayList<String>()
        } else {
            saved[KEY_OPEN] = true
        }
    }

    /** Adds [tagName] (without its namespace) once; @return the filter's search text */
    fun add(tagName: String): String {
        val key = splitNamespace(tagName).last()
        if (key !in tags) saved[KEY_TAGS] = ArrayList(tags + key)
        return searchText()
    }

    /** Drops the last tag while there is more than one (a closed filter has none); @return whether it did */
    fun dropLast(): Boolean {
        if (tags.size <= 1) return false
        saved[KEY_TAGS] = ArrayList(tags.dropLast(1))
        return true
    }

    /** The tags as the search text of [ListUrlBuilder.MODE_FILTER]. */
    fun searchText(): String = tags.joinToString("  ")

    private companion object {
        const val KEY_OPEN = "tag_filter_open"
        const val KEY_TAGS = "tag_filter_tags"
    }
}
