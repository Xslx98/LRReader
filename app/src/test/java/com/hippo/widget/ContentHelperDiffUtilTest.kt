package com.hippo.widget

import androidx.recyclerview.widget.DiffUtil
import com.lanraragi.reader.domain.Archive
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the DiffUtil integration in [ContentLayout.ContentHelper].
 *
 * The ContentHelper.dispatchDiffUpdates() uses:
 * - areItemsTheSame: isDuplicate(d1, d2) → d1.arcid == d2.arcid
 * - areContentsTheSame: compare title
 */
class ContentHelperDiffUtilTest {

    private fun makeInfo(id: Long, title: String = "title$id"): Archive {
        return Archive(
            arcid = "arcid$id", title = title, tags = emptyMap(),
            pagecount = 0, progress = 0, extension = "", filename = "",
            thumbnailUrl = "", rating = 0f, isnew = false, lastreadtime = 0,
            summary = null, serverProfileId = 0
        )
    }

    private fun computeDiff(oldList: List<Archive>, newList: List<Archive>): DiffUtil.DiffResult {
        return DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldList.size
            override fun getNewListSize() = newList.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                oldList[oldPos].arcid == newList[newPos].arcid
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                oldList[oldPos].arcid == newList[newPos].arcid &&
                oldList[oldPos].title == newList[newPos].title
        })
    }

    @Test
    fun identicalLists_noUpdates() {
        val list = listOf(makeInfo(1), makeInfo(2), makeInfo(3))
        val ops = mutableListOf<String>()
        val result = computeDiff(list, list)
        result.dispatchUpdatesTo(object : androidx.recyclerview.widget.ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) { ops.add("insert") }
            override fun onRemoved(position: Int, count: Int) { ops.add("remove") }
            override fun onMoved(from: Int, to: Int) { ops.add("move") }
            override fun onChanged(position: Int, count: Int, payload: Any?) { ops.add("change") }
        })
        assertTrue("Identical lists should produce no updates", ops.isEmpty())
    }

    @Test
    fun appendedItems_onlyInserts() {
        val old = listOf(makeInfo(1), makeInfo(2))
        val new = listOf(makeInfo(1), makeInfo(2), makeInfo(3), makeInfo(4))
        val ops = mutableListOf<String>()
        computeDiff(old, new).dispatchUpdatesTo(object : androidx.recyclerview.widget.ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) { ops.add("insert:$position:$count") }
            override fun onRemoved(position: Int, count: Int) { ops.add("remove") }
            override fun onMoved(from: Int, to: Int) { ops.add("move") }
            override fun onChanged(position: Int, count: Int, payload: Any?) { ops.add("change") }
        })
        assertEquals("Should have 1 insert operation", 1, ops.size)
        assertEquals("insert:2:2", ops[0])
    }

    @Test
    fun completeReplacement_insertsAndRemoves() {
        val old = listOf(makeInfo(1), makeInfo(2), makeInfo(3))
        val new = listOf(makeInfo(10), makeInfo(20), makeInfo(30))
        val inserts = mutableListOf<Int>()
        val removes = mutableListOf<Int>()
        computeDiff(old, new).dispatchUpdatesTo(object : androidx.recyclerview.widget.ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) { inserts.add(count) }
            override fun onRemoved(position: Int, count: Int) { removes.add(count) }
            override fun onMoved(from: Int, to: Int) {}
            override fun onChanged(position: Int, count: Int, payload: Any?) {}
        })
        assertEquals("All 3 old items removed", 3, removes.sum())
        assertEquals("All 3 new items inserted", 3, inserts.sum())
    }

    @Test
    fun emptyToFilled_allInserts() {
        val old = emptyList<Archive>()
        val new = listOf(makeInfo(1), makeInfo(2))
        val ops = mutableListOf<String>()
        computeDiff(old, new).dispatchUpdatesTo(object : androidx.recyclerview.widget.ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) { ops.add("insert:$count") }
            override fun onRemoved(position: Int, count: Int) { ops.add("remove") }
            override fun onMoved(from: Int, to: Int) { ops.add("move") }
            override fun onChanged(position: Int, count: Int, payload: Any?) { ops.add("change") }
        })
        assertEquals(1, ops.size)
        assertEquals("insert:2", ops[0])
    }

    @Test
    fun filledToEmpty_allRemoves() {
        val old = listOf(makeInfo(1), makeInfo(2))
        val new = emptyList<Archive>()
        val ops = mutableListOf<String>()
        computeDiff(old, new).dispatchUpdatesTo(object : androidx.recyclerview.widget.ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) { ops.add("insert") }
            override fun onRemoved(position: Int, count: Int) { ops.add("remove:$count") }
            override fun onMoved(from: Int, to: Int) { ops.add("move") }
            override fun onChanged(position: Int, count: Int, payload: Any?) { ops.add("change") }
        })
        assertEquals(1, ops.size)
        assertEquals("remove:2", ops[0])
    }

    @Test
    fun contentChanged_sameItemsDifferentContent() {
        val old = listOf(makeInfo(1, "old title"), makeInfo(2, "old"))
        val new = listOf(makeInfo(1, "new title"), makeInfo(2, "old"))
        val changes = mutableListOf<Int>()
        computeDiff(old, new).dispatchUpdatesTo(object : androidx.recyclerview.widget.ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) {}
            override fun onRemoved(position: Int, count: Int) {}
            override fun onMoved(from: Int, to: Int) {}
            override fun onChanged(position: Int, count: Int, payload: Any?) { changes.add(position) }
        })
        assertEquals("Only item at position 0 changed", listOf(0), changes)
    }
}
