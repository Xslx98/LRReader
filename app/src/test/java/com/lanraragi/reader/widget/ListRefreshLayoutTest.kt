package com.lanraragi.reader.widget

import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.framework.widget.ContentLayout
import com.lanraragi.reader.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ListRefreshLayoutTest {

    private lateinit var layout: ListRefreshLayout
    private lateinit var list: RecyclerView
    private var headers = 0
    private var footers = 0

    /** 120 dp at the default 160 dpi, under the 60 % of a 1000 px parent. */
    private val trigger = 120

    @Before
    fun setUp() {
        val ctx = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.AppTheme)
        // As in the app: widget_content_layout inflates the list inside it.
        val content = ContentLayout(ctx)
        layout = content.refreshLayout
        list = content.recyclerView
        list.layoutManager = LinearLayoutManager(ctx)
        val exactly = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        content.measure(exactly, exactly)
        content.layout(0, 0, 1000, 1000)
        layout.setOnRefreshListener(object : ListRefreshLayout.OnRefreshListener {
            override fun onHeaderRefresh() { headers++ }
            override fun onFooterRefresh() { footers++ }
        })
    }

    private var time = 0L
    private var y = 900f

    private fun touch(action: Int) {
        val ev = MotionEvent.obtain(0L, time, action, 500f, y, 0)
        time += 16
        layout.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun down() = touch(MotionEvent.ACTION_DOWN)
    private fun up() = touch(MotionEvent.ACTION_UP)

    /** Drags the content up by [dy] px (an empty list is always at its end). */
    private fun drag(dy: Int) {
        y -= dy
        touch(MotionEvent.ACTION_MOVE)
    }

    @Test
    fun aPullPastTheTriggerDistance_startsOneFooterRefresh() {
        down()
        drag(trigger - 1)
        assertEquals(0, footers)
        assertFalse(layout.isFooterRefreshing)
        drag(1)
        assertEquals(1, footers)
        assertTrue(layout.isFooterRefreshing)
        assertTrue(layout.isAnyRefreshing)
        drag(trigger * 2)
        assertEquals("no second load while one runs", 1, footers)
        assertEquals(0, headers)
    }

    @Test
    fun aReleasedShortPull_resets() {
        down()
        drag(trigger - 1)
        up()
        down()
        drag(trigger - 1)
        assertEquals(0, footers)
    }

    @Test
    fun draggingBackDown_takesThePullBack() {
        down()
        drag(trigger - 1)
        drag(-(trigger - 1))
        drag(trigger - 1)
        assertEquals(0, footers)
    }

    @Test
    fun aDisabledLayout_doesNotLoad() {
        layout.isEnabled = false
        down()
        drag(trigger * 2)
        assertEquals(0, footers)
    }

    @Test
    fun noFooterLoadWhileTheHeaderRefreshes() {
        layout.setHeaderRefreshing(true)
        assertTrue(layout.isHeaderRefreshing)
        down()
        drag(trigger * 2)
        assertEquals(0, footers)
    }

    /** Gives the list [rows] rows of 100 px (scrollable past the 1000 px layout). */
    private fun fillList(rows: Int) {
        list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(View(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, 100)
                }) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        }
        relayout()
    }

    private fun relayout() {
        val exactly = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        layout.parent.let { it as View }.run {
            measure(exactly, exactly)
            layout(0, 0, 1000, 1000)
        }
    }

    @Test
    fun theHeader_onlyTakesAGestureThatStartsAtTheTop() {
        fillList(50)
        list.scrollBy(0, 500)
        assertTrue(list.canScrollVertically(-1))
        assertFalse(layout.onStartNestedScroll(list, list, ViewCompat.SCROLL_AXIS_VERTICAL))
        list.scrollBy(0, -500)
        assertTrue(layout.onStartNestedScroll(list, list, ViewCompat.SCROLL_AXIS_VERTICAL))
    }

    @Test
    fun theFooter_onlyCountsAGestureThatStartsAtTheEnd() {
        fillList(50)
        down() // mid-list
        list.scrollBy(0, 10_000) // the same gesture scrolls to the end
        assertFalse(list.canScrollVertically(1))
        drag(trigger * 2)
        up()
        assertEquals(0, footers)
        down() // at the end
        drag(trigger * 2)
        assertEquals(1, footers)
    }

    @Test
    fun footerRefreshing_blocksANewNestedScroll() {
        layout.setFooterRefreshing(true)
        assertFalse(layout.onStartNestedScroll(list, list, ViewCompat.SCROLL_AXIS_VERTICAL))
        layout.setFooterRefreshing(false)
        assertFalse(layout.isAnyRefreshing)
        assertTrue(layout.onStartNestedScroll(list, list, ViewCompat.SCROLL_AXIS_VERTICAL))
    }

    @Test
    fun offsetHeader_movesBothSpinnerPositions() {
        val start = layout.progressViewStartOffset
        val end = layout.progressViewEndOffset
        layout.offsetHeader(50)
        assertEquals(start + 50, layout.progressViewStartOffset)
        assertEquals(end + 50, layout.progressViewEndOffset)
    }

    @Test
    fun anEmptyList_isAtItsBottom() {
        assertTrue(layout.isAlmostBottom())
    }
}
