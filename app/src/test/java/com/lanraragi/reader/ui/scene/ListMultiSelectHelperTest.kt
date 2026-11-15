package com.lanraragi.reader.ui.scene

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lanraragi.reader.widget.list.EasyRecyclerView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Audit 2026-11-15 B4: after a configuration change in multi-select the
 * view restores the mode; the helper must bring back its chrome (mode
 * callback) and the checked count, not only the view flag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ListMultiSelectHelperTest {

    private class Rows : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 10
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(View(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, 100)
            }) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    private val modes = mutableListOf<Boolean>()
    private val counts = mutableListOf<Int>()

    private fun newList(): EasyRecyclerView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val list = EasyRecyclerView(activity)
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = Rows()
        activity.setContentView(FrameLayout(activity).apply { addView(list, 1000, 1000) })
        shadowOf(Looper.getMainLooper()).idle()
        val helper = ListMultiSelectHelper({ list }, { null }, { modes += it }, { counts += it })
        list.setChoiceMode(EasyRecyclerView.CHOICE_MODE_MULTIPLE_CUSTOM)
        list.setCustomCheckedListener(helper.choiceListener)
        return list
    }

    @Test
    fun restoredMode_reportsModeAndCount() {
        val before = newList()
        before.intoCustomChoiceMode()
        before.setItemChecked(2, true)
        before.setItemChecked(5, true)
        val state = before.onSaveInstanceState()
        modes.clear()
        counts.clear()

        newList().onRestoreInstanceState(state)

        assertEquals(listOf(true), modes)
        assertEquals(listOf(2), counts)
    }

    @Test
    fun longPressEntry_reportsNoCountBeforeTheFirstCheck() {
        val list = newList()
        list.intoCustomChoiceMode()

        assertEquals(listOf(true), modes)
        assertEquals(emptyList<Int>(), counts)
    }
}
