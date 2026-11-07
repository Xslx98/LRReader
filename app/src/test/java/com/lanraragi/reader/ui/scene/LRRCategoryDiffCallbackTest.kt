package com.lanraragi.reader.ui.scene

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import com.lanraragi.reader.client.api.data.LRRCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the categories list repaints after a reload: a row is identified by its
 * category id alone, so an edited row updates in place, and a reload that
 * changed nothing the row shows repaints nothing.
 */
class LRRCategoryDiffCallbackTest {

    private fun cat(
        id: String,
        name: String = "Name",
        pinned: Boolean = false,
        search: String? = "",
        archives: List<String> = emptyList(),
    ) = LRRCategory().also {
        it.id = id
        it.name = name
        it.pinned = if (pinned) "1" else "0"
        it.search = search
        it.archives = archives
    }

    private fun updates(old: List<LRRCategory>, new: List<LRRCategory>): List<String> {
        val seen = ArrayList<String>()
        DiffUtil.calculateDiff(LRRCategoriesScene.LRRCategoryDiffCallback(old, new))
            .dispatchUpdatesTo(object : ListUpdateCallback {
                override fun onInserted(position: Int, count: Int) { seen.add("insert $position") }
                override fun onRemoved(position: Int, count: Int) { seen.add("remove $position") }
                override fun onMoved(fromPosition: Int, toPosition: Int) { seen.add("move $fromPosition $toPosition") }
                override fun onChanged(position: Int, count: Int, payload: Any?) { seen.add("change $position") }
            })
        return seen
    }

    @Test
    fun editingWhatARowShows_changesThatRowInPlace() {
        val old = listOf(cat("SET_1"), cat("SET_2"))

        val edits = listOf(
            cat("SET_2", name = "Renamed"),
            cat("SET_2", pinned = true),
            cat("SET_2", search = "artist:x"),
            cat("SET_2", archives = listOf("a1")),
        )

        for (edit in edits) {
            assertEquals(listOf("change 1"), updates(old, listOf(cat("SET_1"), edit)))
        }
    }

    @Test
    fun changingOnlyThePredicateText_repaintsTheRow() {
        val old = listOf(cat("SET_1", search = "artist:x"))

        assertEquals(listOf("change 0"), updates(old, listOf(cat("SET_1", search = "artist:y"))))
    }

    @Test
    fun aReloadOfEqualContent_repaintsNothing_evenThoughTheObjectsAreNew() {
        val load = { listOf(cat("SET_1", archives = listOf("a1")), cat("SET_2", pinned = true, search = "x")) }

        assertEquals(emptyList<String>(), updates(load(), load()))
    }

    @Test
    fun aPinMovesTheRowInsteadOfReplacingIt() {
        val old = listOf(cat("SET_1"), cat("SET_2"))
        val new = listOf(cat("SET_2", pinned = true), cat("SET_1"))

        val seen = updates(old, new)

        assertEquals(seen.toString(), 0, seen.count { it.startsWith("insert") || it.startsWith("remove") })
        assertEquals(seen.toString(), 1, seen.count { it.startsWith("move") })
        assertEquals(seen.toString(), 1, seen.count { it.startsWith("change") })
    }

    @Test
    fun aDifferentId_isADifferentRow_evenWithTheSameContent() {
        val seen = updates(listOf(cat("SET_1")), listOf(cat("SET_2")))

        assertEquals(listOf("insert", "remove"), seen.map { it.substringBefore(' ') }.sorted())
    }
}
