package com.lanraragi.reader.widget.drawer

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [28], application = android.app.Application::class)
class DrawerLayoutTest {

    /** Exposes the inflation hook, as a layout file would call it. */
    private class TestDrawerLayout(context: Context) : DrawerLayout(context) {
        fun finishInflate() = onFinishInflate()
        fun save(): Parcelable = onSaveInstanceState()
        fun restore(state: Parcelable) = onRestoreInstanceState(state)

        @Suppress("DEPRECATION")
        fun insets(rect: Rect) = fitSystemWindows(rect)
    }

    private class InsetChild(context: Context, private val topMargin: Boolean) : FrameLayout(context), DrawerLayoutChild {
        var insetTop = 0
        var insetBottom = 0
        override fun onGetWindowPadding(top: Int, bottom: Int) {
            insetTop = top
            insetBottom = bottom
        }
        override fun getAdditionalTopMargin(): Int = if (topMargin) insetTop else 0
        override fun getAdditionalBottomMargin(): Int = insetBottom
    }

    private lateinit var layout: TestDrawerLayout
    private lateinit var content: InsetChild
    private lateinit var left: InsetChild
    private lateinit var right: View
    private val events = mutableListOf<String>()
    private var time = 0L

    @Before
    fun setUp() {
        // Attached to a window: a drawer switch posts its second half.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val ctx: Context = activity
        layout = TestDrawerLayout(ctx)
        content = InsetChild(ctx, topMargin = true)
        left = InsetChild(ctx, topMargin = false)
        right = View(ctx)
        layout.addView(content, DrawerLayout.LayoutParams(MATCH, MATCH))
        layout.addView(left, DrawerLayout.LayoutParams(400, MATCH, Gravity.LEFT))
        layout.addView(right, DrawerLayout.LayoutParams(MATCH, MATCH, Gravity.RIGHT))
        layout.finishInflate()
        activity.setContentView(layout)
        layout.setDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, percent: Float) = Unit
            override fun onDrawerOpened(drawerView: View) { events += "opened ${name(drawerView)}" }
            override fun onDrawerClosed(drawerView: View) { events += "closed ${name(drawerView)}" }
            override fun onDrawerStateChanged(drawerView: View, newState: Int) = Unit
        })
        settle() // the window lays the drawer out at its own size
        w = layout.width.toFloat()
    }

    /** Layout width after the window's layout pass. */
    private var w = 0f

    private fun name(view: View) = if (view === left) "left" else "right"

    private fun layoutAll() {
        layout.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)
        )
        layout.layout(0, 0, 1000, 2000)
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    private fun touch(action: Int, x: Float) {
        val ev = MotionEvent.obtain(SystemClock.uptimeMillis(), time, action, x, layout.height / 2f, 0)
        time += 16
        layout.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** Drags horizontally across the content from [from] to [to] (px). */
    private fun drag(from: Float, to: Float) {
        touch(MotionEvent.ACTION_DOWN, from)
        val steps = 10
        for (i in 1..steps) touch(MotionEvent.ACTION_MOVE, from + (to - from) * i / steps)
        touch(MotionEvent.ACTION_UP, to)
        settle()
    }

    @Test
    fun aDragOnTheContent_opensTheLeftDrawer() {
        drag(w * 0.3f, w * 0.8f)
        assertTrue(layout.isDrawerOpen(Gravity.LEFT))
        assertTrue(layout.isDrawersVisible)
        assertEquals(listOf("opened left"), events)
        assertEquals(0, left.left)
        assertTrue(left.width > 0)
    }

    @Test
    fun aDragUnderTenPercent_fallsBackClosed() {
        drag(w * 0.3f, w * 0.3f + left.width * 0.05f) // out by less than 10 %
        assertFalse(layout.isDrawerOpen(Gravity.LEFT))
        assertFalse(layout.isDrawersVisible)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun aLeftwardDrag_opensTheRightDrawer() {
        drag(w * 0.7f, w * 0.1f)
        assertTrue(layout.isDrawerOpen(Gravity.RIGHT))
        assertEquals(listOf("opened right"), events)
    }

    @Test
    fun aLockedDrawer_doesNotOpenByDrag() {
        layout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT)
        drag(w * 0.3f, w * 0.9f)
        assertFalse(layout.isDrawerOpen(Gravity.LEFT))
        assertEquals(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, layout.getDrawerLockMode(Gravity.LEFT))
    }

    @Test
    fun aBlockedGesture_doesNotOpen() {
        layout.setGestureBlocker { true }
        drag(w * 0.3f, w * 0.9f)
        assertFalse(layout.isDrawerOpen(Gravity.LEFT))
    }

    @Test
    fun aDragBackFromAnOpenDrawer_closesIt() {
        layout.openDrawer(Gravity.LEFT)
        settle()
        drag(w * 0.9f, w * 0.1f)
        assertFalse(layout.isDrawerOpen(Gravity.LEFT))
        assertEquals(listOf("opened left", "closed left"), events)
    }

    @Test
    fun openingTheOtherSide_closesTheOpenOneFirst() {
        layout.openDrawer(Gravity.LEFT)
        settle()
        layout.openDrawer(Gravity.RIGHT)
        settle()
        assertFalse(layout.isDrawerOpen(Gravity.LEFT))
        assertTrue(layout.isDrawerOpen(Gravity.RIGHT))
        assertEquals(listOf("opened left", "closed left", "opened right"), events)
    }

    @Test
    fun windowInsets_reachTheChildrenAsMargins() {
        layout.insets(Rect(0, 60, 0, 120))
        layout.requestLayout()
        layoutAll()
        assertEquals(60, content.insetTop)
        assertEquals(120, content.insetBottom)
        assertEquals(60, left.insetTop)
        // The content asked for both margins, the drawer only for the bottom one.
        assertEquals(60, content.top)
        assertEquals(2000 - 60 - 120, content.height)
        assertEquals(0, left.top)
        assertEquals(2000 - 120, left.height)
    }

    @Test
    fun savedState_restoresTheOpenDrawerAndLockModes() {
        layout.openDrawer(Gravity.LEFT)
        settle()
        layout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT)
        val state = layout.save()

        setUp()
        layout.restore(state)
        settle()
        assertTrue(layout.isDrawerOpen(Gravity.LEFT))
        assertEquals(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, layout.getDrawerLockMode(Gravity.RIGHT))
    }

    private companion object {
        const val MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT
    }
}
