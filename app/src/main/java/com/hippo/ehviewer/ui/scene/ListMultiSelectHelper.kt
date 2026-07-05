package com.hippo.ehviewer.ui.scene

import androidx.core.util.size
import com.hippo.easyrecyclerview.EasyRecyclerView

/**
 * Minimal driver for [EasyRecyclerView] custom-choice mode shared by list
 * scenes (gallery list, history). Mirrors DownloadSelectionHelper's flow:
 * long-press enters choice mode and checks the pressed row; while the mode
 * is active item clicks toggle rows via [toggleChecked] (the library does
 * not toggle on click by itself).
 *
 * The host must configure the view before the mode can be entered:
 * `setChoiceMode(EasyRecyclerView.CHOICE_MODE_MULTIPLE_CUSTOM)` and
 * `setCustomCheckedListener(helper.choiceListener)` — `intoCustomChoiceMode()`
 * is a no-op under any other choice mode and dispatches to the custom-choice
 * listener without a null check.
 */
class ListMultiSelectHelper(
    private val recyclerView: () -> EasyRecyclerView?,
    private val longClickListener: () -> EasyRecyclerView.OnItemLongClickListener?,
    private val onModeChanged: (active: Boolean) -> Unit,
    private val onCheckedChanged: (checkedCount: Int) -> Unit,
) {

    /** Register via [EasyRecyclerView.setCustomCheckedListener]. */
    val choiceListener: EasyRecyclerView.CustomChoiceListener = ChoiceListenerImpl()

    val isActive: Boolean
        get() = recyclerView()?.isInCustomChoice == true

    /**
     * Long-press entry point: enters choice mode if necessary and toggles the
     * pressed row. Returns true when the gesture was handled.
     */
    fun enterAndCheck(position: Int): Boolean {
        val rv = recyclerView() ?: return false
        if (!rv.isInCustomChoice) {
            rv.intoCustomChoiceMode()
            // Still not in the mode → choice mode was never configured.
            if (!rv.isInCustomChoice) return false
        }
        rv.toggleItemChecked(position)
        return true
    }

    /**
     * Click entry point: toggles the clicked row while the mode is active.
     * Returns false (click not consumed) when the mode is inactive.
     */
    fun toggleChecked(position: Int): Boolean {
        val rv = recyclerView() ?: return false
        if (!rv.isInCustomChoice) return false
        rv.toggleItemChecked(position)
        return true
    }

    fun exit() {
        recyclerView()?.takeIf { it.isInCustomChoice }?.outOfCustomChoiceMode()
    }

    fun checkAll() {
        recyclerView()?.takeIf { it.isInCustomChoice }?.checkAll()
    }

    /** Checked adapter positions, ascending. */
    fun checkedPositions(): List<Int> {
        val rv = recyclerView() ?: return emptyList()
        val stateArray = rv.checkedItemPositions ?: return emptyList()
        val positions = ArrayList<Int>(stateArray.size)
        for (i in 0 until stateArray.size) {
            if (stateArray.valueAt(i)) {
                positions.add(stateArray.keyAt(i))
            }
        }
        return positions
    }

    private inner class ChoiceListenerImpl : EasyRecyclerView.CustomChoiceListener {
        override fun onIntoCustomChoice(view: EasyRecyclerView) {
            // Long-press is the entry gesture; while the mode is active a long
            // press must not fall through to the item context handler. Restoring
            // the listener on exit re-enables isLongClickable inside the library.
            view.setOnItemLongClickListener(null)
            view.isLongClickable = false
            onModeChanged(true)
        }

        override fun onOutOfCustomChoice(view: EasyRecyclerView) {
            view.setOnItemLongClickListener(longClickListener())
            onModeChanged(false)
        }

        override fun onItemCheckedStateChanged(
            view: EasyRecyclerView,
            position: Int,
            id: Long,
            checked: Boolean,
        ) {
            val count = view.checkedItemCount
            if (count == 0) {
                // Unchecking the last row leaves the mode (mirrors downloads).
                // Re-entrancy while outOfCustomChoiceMode() unchecks rows is
                // guarded inside the library.
                view.outOfCustomChoiceMode()
                return
            }
            onCheckedChanged(count)
        }
    }
}
