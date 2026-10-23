package com.lanraragi.reader.widget.list

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** FastScroller, MarginItemDecoration and LayoutManagerUtils. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [28], application = android.app.Application::class)
class ListWidgetsTest {

    private class Rows(private val count: Int) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = count
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(View(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, 100)
            }) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** A 1000 px list of [rows] 100 px rows with [manager], laid out in the activity. */
    private fun list(rows: Int, manager: RecyclerView.LayoutManager, scroller: FastScroller? = null): RecyclerView {
        val list = RecyclerView(activity)
        list.layoutManager = manager
        list.adapter = Rows(rows)
        activity.setContentView(FrameLayout(activity).apply {
            addView(list, 1000, 1000)
            scroller?.let { addView(it, 30, 1000) }
        })
        idle()
        return list
    }

    @Test
    fun fastScroller_showsOnScrollAndHidesAfterTheDelay() {
        val scroller = FastScroller(activity)
        val list = list(50, LinearLayoutManager(activity), scroller)
        scroller.attachToRecyclerView(list)
        assertTrue(scroller.isAttached)
        assertEquals(View.INVISIBLE, scroller.visibility)
        list.scrollBy(0, 100)
        idle()
        assertEquals(View.VISIBLE, scroller.visibility)
        idle(1000 + 500 + 100)
        assertEquals(View.INVISIBLE, scroller.visibility)
    }

    @Test
    fun fastScroller_dragsTheListInProportion() {
        val scroller = FastScroller(activity)
        scroller.setHandlerDrawable(HandlerDrawable())
        val list = list(50, LinearLayoutManager(activity), scroller)
        scroller.attachToRecyclerView(list)
        list.scrollBy(0, 1) // reveal; the 1000 px track shows a 200 px handle at the top
        idle()
        var started = 0
        var ended = 0
        scroller.setOnDragHandlerListener(object : FastScroller.OnDragHandlerListener {
            override fun onStartDragHandler() { started++ }
            override fun onEndDragHandler() { ended++ }
        })
        val down = SystemClock.uptimeMillis()
        fun touch(action: Int, y: Float) {
            val ev = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 15f, y, 0)
            scroller.dispatchTouchEvent(ev)
            ev.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, 50f)
        touch(MotionEvent.ACTION_MOVE, 150f)
        touch(MotionEvent.ACTION_MOVE, 250f)
        touch(MotionEvent.ACTION_UP, 250f)
        // range 5000 px over a 1000 px track: 200 px of handle drag = 1000 px of list.
        assertEquals(1 + 1000, list.computeVerticalScrollOffset())
        assertEquals(1, started)
        assertEquals(1, ended)
    }

    @Test
    fun fastScroller_ignoresADownOffTheHandler() {
        val scroller = FastScroller(activity)
        val list = list(50, LinearLayoutManager(activity), scroller)
        scroller.attachToRecyclerView(list)
        list.scrollBy(0, 1)
        idle()
        val down = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 15f, 900f, 0)
        assertEquals(false, scroller.onTouchEvent(ev))
        ev.recycle()
    }

    @Test
    fun marginItemDecoration_splitsTheGap() {
        val decoration = MarginItemDecoration(20, 30, 40, 50, 60)
        val outRect = Rect()
        decoration.getItemOffsets(outRect, View(activity), RecyclerView(activity), RecyclerView.State())
        assertEquals(Rect(10, 10, 10, 10), outRect)
        val view = View(activity)
        decoration.applyPaddings(view)
        assertEquals(listOf(20, 30, 40, 50), listOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom))
    }

    @Test
    fun layoutManagerUtils_findsVisibleRowsOfLinearAndStaggeredLists() {
        val linear = list(50, LinearLayoutManager(activity))
        LayoutManagerUtils.scrollToPositionWithOffset(linear.layoutManager, 20, 0)
        idle()
        assertEquals(20, LayoutManagerUtils.getFirstVisibleItemPosition(linear.layoutManager))
        assertEquals(29, LayoutManagerUtils.getLastVisibleItemPosition(linear.layoutManager))

        val staggered = list(50, StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL))
        assertEquals(0, LayoutManagerUtils.getFirstVisibleItemPosition(staggered.layoutManager))
        assertEquals(19, LayoutManagerUtils.getLastVisibleItemPosition(staggered.layoutManager))
    }

    @Test
    fun layoutManagerUtils_scrollsProperlyFarAwayByJumping() {
        val linear = list(50, LinearLayoutManager(activity))
        var reported = -1
        LayoutManagerUtils.scrollToPositionProperly(linear.layoutManager, activity, 40) { reported = it }
        idle(200)
        idle()
        assertEquals(40, reported)
        assertEquals(40, LayoutManagerUtils.getFirstVisibleItemPosition(linear.layoutManager))
    }

    @Test(expected = IllegalStateException::class)
    fun layoutManagerUtils_rejectsOtherLayoutManagers() {
        LayoutManagerUtils.getFirstVisibleItemPosition(object : RecyclerView.LayoutManager() {
            override fun generateDefaultLayoutParams() = RecyclerView.LayoutParams(0, 0)
        })
    }

    @Test
    fun layoutManagerUtils_acceptsAGridAsALinearLayout() {
        val grid = list(50, GridLayoutManager(activity, 2))
        assertEquals(0, LayoutManagerUtils.getFirstVisibleItemPosition(grid.layoutManager))
    }
}
