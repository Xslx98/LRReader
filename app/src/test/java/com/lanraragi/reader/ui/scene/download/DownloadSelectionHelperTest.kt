package com.lanraragi.reader.ui.scene.download

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.SavedStateHandle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lanraragi.framework.drawable.AddDeleteDrawable
import com.lanraragi.framework.widget.FabLayout
import com.lanraragi.reader.ui.list.SelectionModel
import com.lanraragi.reader.widget.drawer.DrawerLayout
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
 * The downloads list's multi-select follows the screen's [SelectionModel]:
 * the FAB enters it with nothing picked, checks stay on their rows when Room
 * re-emits the list, and a re-created screen comes back selecting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class DownloadSelectionHelperTest {

    private class Rows(var keys: List<String>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = keys.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(View(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, 100)
            }) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    private val rows = Rows(listOf("a", "TANK_1", "b", "c"))
    private val drawerLocks = mutableListOf<Int>()
    private lateinit var list: EasyRecyclerView
    private val contextMenu = EasyRecyclerView.OnItemLongClickListener { _, _, _, _ -> true }

    private fun bind(model: SelectionModel): DownloadSelectionHelper {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        list = EasyRecyclerView(activity)
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = rows
        list.setOnItemLongClickListener(contextMenu)
        activity.setContentView(FrameLayout(activity).apply { addView(list, 1000, 1000) })
        idle()
        val helper = DownloadSelectionHelper(object : DownloadSelectionHelper.Callback {
            override val mRecyclerView: EasyRecyclerView get() = list
            override val mFabLayout: FabLayout? = null
            override val actionFabDrawable: AddDeleteDrawable? = null
            override val longClickListener = contextMenu
            override val selection = model
            override fun keyAt(position: Int): String? = rows.keys.getOrNull(position)
            override fun setDrawerLockMode(lockMode: Int, gravity: Int) {
                drawerLocks += lockMode
            }
        })
        helper.attach(list)
        return helper
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun drawnChecked(): List<Int> = (0 until list.childCount)
        .map { list.getChildAt(it) }
        .filter { it.isActivated }
        .map { list.getChildAdapterPosition(it) }
        .sorted()

    @Test
    fun theFabEntersWithNothingPicked_andLeaves() {
        val model = SelectionModel()
        val helper = bind(model)
        val fab = FabLayout(list.context)

        helper.onClickPrimaryFab(fab, null)
        assertTrue(helper.isActive)
        assertEquals(emptySet<String>(), helper.checkedKeys)
        assertEquals(listOf(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, DrawerLayout.LOCK_MODE_LOCKED_CLOSED), drawerLocks)
        assertFalse(list.isLongClickable)

        assertTrue(helper.toggleChecked(1))
        assertEquals(setOf("TANK_1"), helper.checkedKeys)
        assertEquals(listOf(1), drawnChecked())

        helper.onClickPrimaryFab(fab, null)
        assertFalse(helper.isActive)
        assertEquals(DrawerLayout.LOCK_MODE_UNLOCKED, drawerLocks.last())
        assertTrue(list.isLongClickable)
        assertEquals(emptyList<Int>(), drawnChecked())
    }

    @Test
    fun longPressPicksTheRow_andUncheckingTheLastLeaves() {
        val helper = bind(SelectionModel())

        assertTrue(helper.onItemLongClick(2))
        assertEquals(setOf("b"), helper.checkedKeys)

        helper.toggleChecked(2)
        assertFalse(helper.isActive)
        assertFalse(helper.toggleChecked(2))
    }

    @Test
    fun aRoomUpdate_keepsEveryCheckOnItsRow() {
        val helper = bind(SelectionModel())
        helper.onItemLongClick(2) // b
        helper.toggleChecked(3) // c

        // "a" finished and left the label; the rest shift up one.
        rows.keys = listOf("TANK_1", "b", "c")
        rows.notifyItemRemoved(0)
        helper.retainAll(rows.keys)
        idle()

        assertEquals(setOf("b", "c"), helper.checkedKeys)
        assertEquals(listOf(1, 2), drawnChecked())

        rows.keys = listOf("TANK_1", "c")
        rows.notifyItemRemoved(1)
        helper.retainAll(rows.keys)
        idle()
        assertEquals(setOf("c"), helper.checkedKeys)
    }

    @Test
    fun aRecreatedScreen_comesBackSelecting() {
        val saved = SavedStateHandle()
        SelectionModel(saved).toggle("c")

        bind(SelectionModel(saved))

        assertEquals(listOf(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, DrawerLayout.LOCK_MODE_LOCKED_CLOSED), drawerLocks)
        assertEquals(listOf(3), drawnChecked())
        assertFalse(list.isLongClickable)
    }
}
