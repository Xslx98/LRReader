package com.lanraragi.reader.ui.scene.download

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lanraragi.reader.dao.DownloadInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A paginated adapter holds one page, so a diff in full-list positions
 * would notify rows it does not have; it rebinds instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class DownloadListDispatcherTest {

    private class Recorder : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        val events = mutableListOf<String>()

        init {
            registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() { events += "all" }
                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { events += "removed $positionStart" }
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { events += "inserted $positionStart" }
                override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) { events += "moved" }
                override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) { events += "changed" }
            })
        }

        override fun getItemCount() = 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(View(parent.context)) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    private fun rows(vararg ids: String) = ids.map { id -> DownloadInfo().also { it.arcid = id } }

    @Test
    fun aPagedListRebinds_anUnpagedOneIsDiffed() {
        val paged = Recorder()
        DownloadListDispatcher.dispatchTo(paged, rows("a", "b", "c"), rows("a", "c"), paged = true)
        assertEquals(listOf("all"), paged.events)

        val whole = Recorder()
        DownloadListDispatcher.dispatchTo(whole, rows("a", "b", "c"), rows("a", "c"), paged = false)
        assertEquals(listOf("removed 1"), whole.events)
    }
}
