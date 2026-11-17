package com.lanraragi.reader.ui.scene

import com.lanraragi.reader.ui.list.SelectionModel
import com.lanraragi.reader.ui.list.SelectionState
import com.lanraragi.reader.widget.list.EasyRecyclerView

/**
 * Multi-select on a list scene (gallery list, history), driven by the
 * screen's [SelectionModel]: long-press enters the mode with the pressed row,
 * clicks toggle rows while it is on, and the view only draws the model
 * ([EasyRecyclerView.checkedProvider]). Rows are named by [keyAt], so a list
 * that changes under the selection keeps every check on its own row.
 *
 * The model outlives the view (it lives in the ViewModel); a helper bound to
 * a model that is already selecting (a rotation, a process-death restore)
 * brings the mode chrome back at once.
 */
class ListMultiSelectHelper(
    private val model: SelectionModel,
    private val recyclerView: () -> EasyRecyclerView?,
    /** The key of the row at an adapter position, or null past the end. */
    private val keyAt: (position: Int) -> String?,
    /** The keys "select all" picks: every selectable row of the list. */
    private val selectableKeys: () -> List<String>,
    private val longClickListener: () -> EasyRecyclerView.OnItemLongClickListener?,
    private val onModeChanged: (active: Boolean) -> Unit,
    private val onCheckedChanged: (checkedCount: Int) -> Unit,
) {

    private var dragSelect: DragSelectTouchListener? = null

    /** The mode the chrome was last told about. */
    private var shownActive = false

    val isActive: Boolean get() = model.isActive

    /** The selected row keys, in the order they were picked. */
    val checkedKeys: Set<String> get() = model.checked

    /**
     * Binds the model to [rv]: rows draw from it, and its changes reach the
     * chrome. Call once per list view; a selection already on shows at once.
     */
    fun attach(rv: EasyRecyclerView) {
        rv.checkedProvider = { position -> keyAt(position)?.let(model::isSelected) == true }
        model.onChanged = ::render
        render(model.state)
    }

    /** Stops drawing and reporting; the selection itself stays in the model. */
    fun detach() {
        dragSelect?.cancel()
        model.onChanged = null
        recyclerView()?.checkedProvider = null
    }

    /**
     * Enables drag-to-select on [rv] (spec 2026-09-22-drag-select): the
     * long-press that enters the mode keeps sliding to extend the selection.
     * [canSelect] rows the drag passes over but never checks.
     */
    fun attachDragSelect(rv: EasyRecyclerView, canSelect: (Int) -> Boolean) {
        val listener = DragSelectTouchListener(rv, dragSelection(rv, canSelect))
        rv.addOnItemTouchListener(listener)
        dragSelect = listener
    }

    /**
     * Long-press entry point: toggles the pressed row, entering the mode with
     * it. Returns true when the gesture was handled.
     */
    fun enterAndCheck(position: Int): Boolean {
        val key = keyAt(position) ?: return false
        model.toggle(key)
        // The finger is still down: the same touch stream becomes a drag.
        dragSelect?.startDrag(position)
        return true
    }

    /**
     * Click entry point: toggles the clicked row while the mode is active.
     * Returns false (click not consumed) when the mode is inactive.
     */
    fun toggleChecked(position: Int): Boolean {
        if (!model.isActive) return false
        keyAt(position)?.let(model::toggle)
        return true
    }

    /** Unchecks [key] if it is selected (its row left the list). */
    fun forget(key: String) = model.setSelected(key, false)

    /** Drops the selected keys the list no longer holds. */
    fun retainAll(keys: Collection<String>) = model.retainAll(keys)

    fun exit() {
        dragSelect?.cancel()
        model.exit()
    }

    fun checkAll() = model.selectAll(selectableKeys())

    private fun render(state: SelectionState) {
        val active = state is SelectionState.Selecting
        if (active != shownActive) {
            shownActive = active
            recyclerView()?.let { rv ->
                if (active) {
                    // Long-press is the entry gesture; while the mode is on a
                    // long press must not fall through to the item context
                    // handler (setting a null listener re-enables
                    // isLongClickable, hence the order).
                    rv.setOnItemLongClickListener(null)
                    rv.isLongClickable = false
                } else {
                    rv.setOnItemLongClickListener(longClickListener())
                }
            }
            if (!active) dragSelect?.cancel()
            onModeChanged(active)
        }
        if (state is SelectionState.Selecting) onCheckedChanged(state.checked.size)
        recyclerView()?.refreshChecked()
    }

    /** The drag's view of the selection: adapter positions over [model]. */
    private fun dragSelection(rv: EasyRecyclerView, canSelect: (Int) -> Boolean) =
        object : DragSelectTouchListener.Selection {
            override fun isActive(): Boolean = model.isActive

            override fun checkedPositions(): Set<Int> {
                val checked = model.checked
                if (checked.isEmpty()) return emptySet()
                val count = rv.adapter?.itemCount ?: 0
                return (0 until count).filterTo(LinkedHashSet()) { keyAt(it)?.let(checked::contains) == true }
            }

            override fun setChecked(position: Int, checked: Boolean) {
                keyAt(position)?.let { model.setSelected(it, checked) }
            }

            override fun canSelect(position: Int): Boolean = canSelect(position)
        }
}
