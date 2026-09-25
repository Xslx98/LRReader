package com.lanraragi.framework.widget

import android.content.Context
import android.os.Parcelable
import androidx.test.core.app.ApplicationProvider
import com.hippo.refreshlayout.RefreshLayout
import kotlinx.parcelize.Parcelize
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ContentLayout.ContentHelper] turns each page result into granular adapter
 * notifications (audit A64): a whole-list refresh is diffed, a very long list
 * falls back to one remove + one insert, and a single-page refresh is diffed
 * only inside that page's window, shifted to the page's adapter position.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ContentHelperDiffDispatchTest {

    @Parcelize
    data class Row(val id: String, val rev: Int = 0) : Parcelable

    private class RecordingHelper(private val ctx: Context) : ContentLayout.ContentHelper<Row>() {
        val calls = mutableListOf<String>()
        var lastTaskId = -1
        var lastType = -1
        var lastPage = -1

        override fun getPageData(taskId: Int, type: Int, page: Int) {
            lastTaskId = taskId; lastType = type; lastPage = page
        }
        override fun getPageData(taskId: Int, type: Int, page: Int, append: String?) =
            getPageData(taskId, type, page)
        override fun getExPageData(pageAction: Int, taskId: Int, page: Int) = Unit
        override fun getContext(): Context = ctx
        override fun notifyDataSetChanged() { calls += "all" }
        override fun notifyItemRangeRemoved(positionStart: Int, itemCount: Int) { calls += "remove $positionStart+$itemCount" }
        override fun notifyItemRangeInserted(positionStart: Int, itemCount: Int) { calls += "insert $positionStart+$itemCount" }
        override fun notifyItemRangeChanged(positionStart: Int, itemCount: Int) { calls += "change $positionStart+$itemCount" }
        override fun notifyItemMoved(fromPosition: Int, toPosition: Int) { calls += "move $fromPosition>$toPosition" }
        override fun isDuplicate(d1: Row, d2: Row): Boolean = d1.id == d2.id

        /** Answer the request the helper just issued. */
        fun deliver(rows: List<Row>, pages: Int, nextPage: Int) {
            calls.clear()
            onGetPageData(lastTaskId, pages, nextPage, rows)
        }
    }

    private lateinit var layout: ContentLayout
    private lateinit var helper: RecordingHelper

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ctx.setTheme(com.lanraragi.reader.R.style.AppTheme)
        layout = ContentLayout(ctx)
        helper = RecordingHelper(ctx)
        layout.setHelper(helper)
    }

    private fun rows(vararg ids: String) = ids.map { Row(it) }

    /**
     * The only way into a single-page refresh is the user pulling the footer
     * on the last loaded page; RefreshLayout (pinned 0.1.0) has no public
     * trigger, so invoke the listener it holds, as its gesture code does.
     */
    private fun pullFooter() {
        val field = RefreshLayout::class.java.getDeclaredField("mListener")
        field.isAccessible = true
        (field.get(layout.refreshLayout) as RefreshLayout.OnRefreshListener).onFooterRefresh()
    }

    @Test
    fun refresh_dispatchesOnlyTheRowsThatChanged() {
        helper.refresh()
        helper.deliver(rows("a", "b", "c"), pages = 1, nextPage = 1)

        helper.refresh()
        helper.deliver(listOf(Row("a"), Row("b", rev = 1), Row("c"), Row("d")), pages = 1, nextPage = 1)

        assertEquals(listOf("insert 3+1", "change 1+1"), helper.calls)
    }

    @Test
    fun refresh_pastTheDiffLimit_replacesTheListWithTwoRangeNotifications() {
        val big = (0 until 201).map { Row("r$it") }
        helper.refresh()
        helper.deliver(big, pages = 1, nextPage = 1)

        helper.refresh()
        helper.deliver(big.map { it.copy(rev = 1) }, pages = 1, nextPage = 1)

        assertEquals(listOf("remove 0+201", "insert 0+201"), helper.calls)
    }

    @Test
    fun refreshingTheLastPage_diffsOnlyThatPageAtItsAdapterOffset() {
        helper.refresh()
        helper.deliver(rows("a", "b", "c"), pages = 2, nextPage = 1)
        pullFooter() // next page
        assertEquals(ContentLayout.ContentHelper.TYPE_NEXT_PAGE_KEEP_POS, helper.lastType)
        helper.deliver(rows("d", "e"), pages = 2, nextPage = 2)

        pullFooter() // last page loaded: refresh it in place
        assertEquals(ContentLayout.ContentHelper.TYPE_REFRESH_PAGE, helper.lastType)
        assertEquals(1, helper.lastPage)
        helper.deliver(listOf(Row("d"), Row("e", rev = 1), Row("f")), pages = 2, nextPage = 2)

        // Page 1 starts at adapter position 3, behind page 0's three rows.
        assertEquals(listOf("insert 5+1", "change 4+1"), helper.calls)
    }
}
