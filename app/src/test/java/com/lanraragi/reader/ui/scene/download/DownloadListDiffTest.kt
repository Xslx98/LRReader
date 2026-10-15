package com.lanraragi.reader.ui.scene.download

import androidx.recyclerview.widget.ListUpdateCallback
import com.lanraragi.reader.dao.DownloadInfo
import com.lanraragi.reader.download.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Audit PERF-09 / C17: a long list rewritten wholesale skips the main-thread diff. */
class DownloadListDiffTest {

    private fun rows(n: Int, prefix: String = "arc") = (0 until n).map { i ->
        DownloadInfo().apply { arcid = "$prefix$i"; title = "T$i"; state = DownloadState.FINISH }
    }

    private class Recorder : ListUpdateCallback {
        val ops = ArrayList<String>()
        override fun onInserted(position: Int, count: Int) { ops += "ins $position+$count" }
        override fun onRemoved(position: Int, count: Int) { ops += "rem $position+$count" }
        override fun onMoved(fromPosition: Int, toPosition: Int) { ops += "mov $fromPosition>$toPosition" }
        override fun onChanged(position: Int, count: Int, payload: Any?) { ops += "chg $position+$count" }
    }

    private fun dispatch(old: List<DownloadInfo>, new: List<DownloadInfo>) =
        Recorder().also { DownloadListDiff.dispatch(old, new, it) }.ops

    @Test
    fun longList_oneRowChangesState_isStillDiffedToThatRow() {
        val old = rows(3000)
        val new = rows(3000).also { it[1500].state = DownloadState.DOWNLOAD }

        assertEquals(listOf("chg 1500+1"), dispatch(old, new))
    }

    @Test
    fun longList_oneDownloadAdded_isStillDiffedToOneInsert() {
        val old = rows(3000)
        val new = listOf(DownloadInfo().apply { arcid = "new"; title = "N" }) + rows(3000)

        assertEquals(listOf("ins 0+1"), dispatch(old, new))
    }

    @Test
    fun longList_reordered_isReplacedNotDiffed() {
        assertEquals(listOf("rem 0+3000", "ins 0+3000"), dispatch(rows(3000), rows(3000).reversed()))
    }

    @Test
    fun longList_anotherLabel_isReplacedNotDiffed() {
        // Half the rows stay, interleaved with the other label's: DiffUtil would
        // emit hundreds of scattered removes.
        val new = rows(3000).filterIndexed { i, _ -> i % 2 == 0 } + rows(1000, prefix = "other")
        assertEquals(listOf("rem 0+3000", "ins 0+2500"), dispatch(rows(3000), new))
    }

    @Test
    fun shortList_reordered_isStillDiffed() {
        val ops = dispatch(rows(50), rows(50).reversed())
        assertEquals("moves, not a replace", true, ops.all { it.startsWith("mov") })
    }

    @Test
    fun editEstimate_countsRowsInOneListAndOrderBreaks() {
        val base = rows(10)
        assertEquals(0, DownloadListDiff.editEstimate(base, rows(10)))
        assertEquals(1, DownloadListDiff.editEstimate(base, rows(10).drop(1)))
        assertEquals("one row moved to the front", 1, DownloadListDiff.editEstimate(base, listOf(base[9]) + base.dropLast(1)))
        assertEquals(9, DownloadListDiff.editEstimate(base, base.reversed()))
    }
}
