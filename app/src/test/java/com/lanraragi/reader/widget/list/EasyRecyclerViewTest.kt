package com.lanraragi.reader.widget.list

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
class EasyRecyclerViewTest {

    private lateinit var list: EasyRecyclerView

    private class Rows(private val count: Int) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = count
        override fun getItemId(position: Int) = 100L + position
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(View(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, 100)
            }) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        list = EasyRecyclerView(activity)
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = Rows(20)
        activity.setContentView(FrameLayout(activity).apply { addView(list, 1000, 1000) })
        idle()
    }

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun row(position: Int): View = list.findViewHolderForAdapterPosition(position)!!.itemView

    private fun touch(action: Int, y: Float, down: Long) {
        val ev = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 500f, y, 0)
        list.dispatchTouchEvent(ev)
        ev.recycle()
    }

    @Test
    fun rowsAreDrawnCheckedFromTheProvider() {
        val checked = mutableSetOf(1, 4)
        list.checkedProvider = { it in checked }
        assertTrue(row(1).isActivated)
        assertTrue(row(4).isActivated)
        assertFalse(row(3).isActivated)

        checked -= 4
        checked += 3
        list.refreshChecked()
        assertFalse(row(4).isActivated)
        assertTrue(row(3).isActivated)

        // A row scrolled in is drawn as it attaches.
        checked += 19
        list.scrollToPosition(19)
        idle()
        assertTrue(row(19).isActivated)
    }

    @Test
    fun aTap_clicksTheRowUnderTheFinger() {
        var clicked = -1
        list.setOnItemClickListener { _, _, position, id ->
            clicked = position
            assertEquals(100L + position, id)
            true
        }
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, 250f, down)
        idle(200)
        assertTrue("pressed after the tap timeout", row(2).isPressed)
        touch(MotionEvent.ACTION_UP, 250f, down)
        idle(500)
        assertEquals(2, clicked)
        assertFalse(row(2).isPressed)
    }

    @Test
    fun aHeldPress_longClicksInsteadOfClicking() {
        var clicked = -1
        var longClicked = -1
        list.setOnItemClickListener { _, _, position, _ -> clicked = position; true }
        list.setOnItemLongClickListener { _, _, position, _ -> longClicked = position; true }
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, 550f, down)
        idle(1000)
        touch(MotionEvent.ACTION_UP, 550f, down)
        idle(500)
        assertEquals(5, longClicked)
        assertEquals(-1, clicked)
    }

    @Test
    fun aDrag_neitherClicksNorLongClicks() {
        var clicked = -1
        list.setOnItemClickListener { _, _, position, _ -> clicked = position; true }
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, 550f, down)
        touch(MotionEvent.ACTION_MOVE, 450f, down)
        touch(MotionEvent.ACTION_UP, 450f, down)
        idle(1000)
        assertEquals(-1, clicked)
    }
}
