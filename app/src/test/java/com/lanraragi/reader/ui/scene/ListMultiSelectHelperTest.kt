package com.lanraragi.reader.ui.scene

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.SavedStateHandle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lanraragi.reader.ui.list.SelectionModel
import com.lanraragi.reader.widget.list.EasyRecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The list draws and reports the screen's [SelectionModel]: checks stay on
 * their rows when the list changes, and a screen re-created mid-selection
 * comes back with its chrome (audit 2026-11-15 B4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ListMultiSelectHelperTest {

    private class Rows(var keys: List<String>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = keys.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(View(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, 100)
            }) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    private val modes = mutableListOf<Boolean>()
    private val counts = mutableListOf<Int>()
    private val rows = Rows(List(8) { "k$it" })
    private lateinit var list: EasyRecyclerView
    private val contextMenu = EasyRecyclerView.OnItemLongClickListener { _, _, _, _ -> true }

    private fun bind(model: SelectionModel): ListMultiSelectHelper {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        list = EasyRecyclerView(activity)
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = rows
        list.setOnItemLongClickListener(contextMenu)
        activity.setContentView(FrameLayout(activity).apply { addView(list, 1000, 1000) })
        idle()
        val helper = ListMultiSelectHelper(
            model = model,
            recyclerView = { list },
            keyAt = { rows.keys.getOrNull(it) },
            selectableKeys = { rows.keys },
            longClickListener = { contextMenu },
            onModeChanged = { modes += it },
            onCheckedChanged = { counts += it },
        )
        helper.attach(list)
        return helper
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The adapter positions drawn as checked. */
    private fun drawnChecked(): List<Int> = (0 until list.childCount)
        .map { list.getChildAt(it) }
        .filter { it.isActivated }
        .map { list.getChildAdapterPosition(it) }
        .sorted()

    @Test
    fun longPressEntersWithTheRow_andTheLastUncheckLeaves() {
        val helper = bind(SelectionModel())

        assertTrue(helper.enterAndCheck(1))
        assertTrue(helper.toggleChecked(3))
        assertEquals(listOf(true), modes)
        assertEquals(listOf(1, 2), counts)
        assertEquals(listOf(1, 3), drawnChecked())
        assertFalse(list.isLongClickable)

        helper.toggleChecked(1)
        helper.toggleChecked(3)
        assertEquals(listOf(true, false), modes)
        assertEquals(emptyList<Int>(), drawnChecked())
        assertTrue(list.isLongClickable)
        assertFalse(helper.toggleChecked(2))
    }

    @Test
    fun checksFollowTheirRows_whenTheListChangesUnderThem() {
        val helper = bind(SelectionModel())
        helper.enterAndCheck(2) // k2

        rows.keys = listOf("new") + rows.keys
        rows.notifyItemInserted(0)
        idle()

        assertEquals(listOf(3), drawnChecked())
        assertEquals(setOf("k2"), helper.checkedKeys)
    }

    @Test
    fun aScreenRecreatedMidSelection_comesBackWithItsChromeAndChecks() {
        val saved = SavedStateHandle()
        SelectionModel(saved).apply {
            toggle("k2")
            toggle("k5")
        }

        bind(SelectionModel(saved))

        assertEquals(listOf(true), modes)
        assertEquals(listOf(2), counts)
        assertEquals(listOf(2, 5), drawnChecked())
        assertFalse(list.isLongClickable)
    }

    @Test
    fun aCheckedRowScrolledIntoView_isDrawnChecked() {
        rows.keys = List(40) { "k$it" }
        val model = SelectionModel()
        bind(model)
        model.toggle("k30")

        list.scrollToPosition(30)
        idle()

        assertTrue(30 in drawnChecked())
    }

    @Test
    fun aDetachedHelper_leavesTheOldViewAlone() {
        val model = SelectionModel()
        val helper = bind(model)
        helper.detach()

        model.toggle("k1")

        assertEquals(emptyList<Boolean>(), modes)
        assertEquals(emptyList<Int>(), drawnChecked())
        assertTrue(list.isLongClickable)
    }

    @Test
    fun selectAll_checksEverySelectableRow() {
        val helper = bind(SelectionModel())
        helper.enterAndCheck(0)

        helper.checkAll()

        assertEquals(rows.keys.toSet(), helper.checkedKeys)
        assertEquals((0 until list.childCount).toList(), drawnChecked())
    }
}
