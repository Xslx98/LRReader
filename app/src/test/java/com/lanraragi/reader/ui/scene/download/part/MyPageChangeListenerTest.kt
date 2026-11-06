package com.lanraragi.reader.ui.scene.download.part

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lanraragi.reader.widget.PageWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class MyPageChangeListenerTest {

    /** Item count follows the page state the way DownloadAdapter's does. */
    private inner class PagedAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = PageWindow.itemsOnPage(TOTAL, pageSize, page)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            error("not laid out")

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    private var page = 1
    private var pageSize = 100
    private val notifications = mutableListOf<String>()

    private fun listener(startPage: Int, startSize: Int): MyPageChangeListener {
        page = startPage
        pageSize = startSize
        val adapter = PagedAdapter()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                notifications += "changed $positionStart $itemCount"
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                notifications += "inserted $positionStart $itemCount"
            }

            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                notifications += "removed $positionStart $itemCount"
            }
        })
        return MyPageChangeListener(startPage, startSize, adapter, null).apply {
            pageChangeCallback = object : MyPageChangeListener.PageChangeCallback {
                override fun onPageChanged(newIndexPage: Int) {
                    page = newIndexPage
                }

                override fun onPageSizeChanged(newPageSize: Int) {
                    pageSize = newPageSize
                }
            }
        }
    }

    @Test
    fun `turning onto the short last page removes the rows it lacks`() {
        val listener = listener(startPage = 1, startSize = 100)

        listener.moveToPage(6) // 550 items: page 6 has 50 rows.

        assertEquals(6, page)
        assertEquals(listOf("changed 0 50", "removed 50 50"), notifications)
    }

    @Test
    fun `turning to the current page does nothing`() {
        val listener = listener(startPage = 3, startSize = 100)

        assertFalse(listener.moveToPage(3))

        assertEquals(emptyList<String>(), notifications)
    }

    @Test
    fun `a new page size goes to page 1 before the adapter is told`() {
        // At page 6 of 50 rows per page, 500 per page would leave no valid item
        // count: the page must already be 1 when the adapter is asked.
        val listener = listener(startPage = 6, startSize = 50)

        listener.onPageSizeSelected(500)

        assertEquals(1, page)
        assertEquals(500, pageSize)
        assertEquals(listOf("changed 0 50", "inserted 50 450"), notifications)
    }

    private companion object {
        const val TOTAL = 550
    }
}
