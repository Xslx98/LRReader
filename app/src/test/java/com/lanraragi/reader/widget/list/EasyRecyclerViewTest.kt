package com.lanraragi.reader.widget.list

import android.app.Activity
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private val events = mutableListOf<String>()

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
        list.setChoiceMode(EasyRecyclerView.CHOICE_MODE_MULTIPLE_CUSTOM)
        list.setCustomCheckedListener(object : EasyRecyclerView.CustomChoiceListener {
            override fun onIntoCustomChoice(view: EasyRecyclerView) { events += "into" }
            override fun onOutOfCustomChoice(view: EasyRecyclerView) { events += "out" }
            override fun onItemCheckedStateChanged(view: EasyRecyclerView, position: Int, id: Long, checked: Boolean) {
                events += "$position/$id=$checked"
            }
        })
    }

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun row(position: Int): View = list.findViewHolderForAdapterPosition(position)!!.itemView

    private fun touch(action: Int, y: Float, down: Long) {
        val ev = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 500f, y, 0)
        list.dispatchTouchEvent(ev)
        ev.recycle()
    }

    @Test(expected = IllegalStateException::class)
    fun checkingOutsideTheChoiceMode_throws() {
        list.setItemChecked(1, true)
    }

    @Test
    fun theCustomChoiceMode_tracksChecksAndUnchecksEverythingOnTheWayOut() {
        list.intoCustomChoiceMode()
        assertTrue(list.isInCustomChoice)
        list.setItemChecked(1, true)
        list.toggleItemChecked(3)
        list.toggleItemChecked(3)
        list.toggleItemChecked(4)
        assertEquals(2, list.checkedItemCount)
        assertTrue(list.isItemChecked(1))
        assertFalse(list.isItemChecked(3))
        assertTrue(row(4).isActivated)
        assertFalse(row(3).isActivated)

        list.outOfCustomChoiceMode()
        assertFalse(list.isInCustomChoice)
        assertEquals(0, list.checkedItemCount)
        assertFalse(row(4).isActivated)
        assertEquals(
            listOf("into", "1/101=true", "3/103=true", "3/103=false", "4/104=true", "1/101=false", "4/104=false", "out"),
            events
        )
    }

    @Test
    fun checkAll_checksEveryItemOnce() {
        list.intoCustomChoiceMode()
        list.setItemChecked(2, true)
        list.checkAll()
        assertEquals(20, list.checkedItemCount)
        assertTrue(list.checkedItemPositions!!.get(19))
    }

    @Test
    fun withoutAChoiceMode_nothingIsChecked() {
        list.setChoiceMode(EasyRecyclerView.CHOICE_MODE_NONE)
        assertNull(list.checkedItemPositions)
        assertFalse(list.isItemChecked(0))
        list.intoCustomChoiceMode()
        assertFalse(list.isInCustomChoice)
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

    @Test
    fun savedState_keepsTheChoiceMode() {
        list.intoCustomChoiceMode()
        list.setItemChecked(1, true)
        list.setItemChecked(6, true)
        val parcel = Parcel.obtain()
        list.onSaveInstanceState().writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        val restored = EasyRecyclerView.SavedState.CREATOR.createFromParcel(parcel)
        parcel.recycle()

        setUp()
        list.onRestoreInstanceState(restored)
        assertTrue(list.isInCustomChoice)
        assertEquals(2, list.checkedItemCount)
        assertTrue(list.isItemChecked(6))
        assertTrue(row(6).isActivated)
    }
}
