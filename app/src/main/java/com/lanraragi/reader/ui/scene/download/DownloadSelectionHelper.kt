/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lanraragi.reader.ui.scene.download

import android.view.Gravity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.lanraragi.framework.drawable.AddDeleteDrawable
import com.lanraragi.reader.ui.list.SelectionModel
import com.lanraragi.reader.ui.list.SelectionState
import com.lanraragi.reader.widget.drawer.DrawerLayout
import com.lanraragi.reader.widget.list.EasyRecyclerView
import com.lanraragi.reader.ui.scene.DragSelectTouchListener
import com.lanraragi.framework.widget.FabLayout

/**
 * Multi-select on [DownloadsScene], driven by the screen's [SelectionModel]
 * (rows keyed by arcid; tank cards by their TANK_ id): the FAB, drawer
 * locking and the list's checked look follow the model. The model lives in
 * the ViewModel, so a Room update, a page switch or a rotation keeps every
 * check on its own row.
 */
internal class DownloadSelectionHelper(private val callback: Callback) {

    interface Callback {
        val mRecyclerView: EasyRecyclerView?
        val mFabLayout: FabLayout?
        val actionFabDrawable: AddDeleteDrawable?
        val longClickListener: EasyRecyclerView.OnItemLongClickListener
        val selection: SelectionModel

        /** The arcid of the row at adapter [position] (a page position), or null. */
        fun keyAt(position: Int): String?
        fun setDrawerLockMode(lockMode: Int, gravity: Int)
    }

    private val model: SelectionModel get() = callback.selection

    private var dragSelect: DragSelectTouchListener? = null

    /** The mode the chrome was last told about. */
    private var shownActive = false

    val isActive: Boolean get() = model.isActive

    /** The selected arcids, in the order they were picked. */
    val checkedKeys: Set<String> get() = model.checked

    /** Binds the model to [rv]; a selection already on shows at once. */
    fun attach(rv: EasyRecyclerView) {
        rv.checkedProvider = { position -> callback.keyAt(position)?.let(model::isSelected) == true }
        model.onChanged = ::render
        render(model.state)
    }

    /** Stops drawing and reporting; the selection itself stays in the model. */
    fun detach() {
        dragSelect?.cancel()
        model.onChanged = null
        callback.mRecyclerView?.checkedProvider = null
    }

    /** Enables drag-to-select on [rv]; every download row (tank cards included) is selectable. */
    fun attachDragSelect(rv: EasyRecyclerView) {
        val listener = DragSelectTouchListener(rv, object : DragSelectTouchListener.Selection {
            override fun isActive(): Boolean = model.isActive
            override fun checkedPositions(): Set<Int> {
                val checked = model.checked
                if (checked.isEmpty()) return emptySet()
                val count = rv.adapter?.itemCount ?: 0
                return (0 until count).filterTo(LinkedHashSet()) { callback.keyAt(it)?.let(checked::contains) == true }
            }
            override fun setChecked(position: Int, checked: Boolean) {
                callback.keyAt(position)?.let { model.setSelected(it, checked) }
            }
            override fun canSelect(position: Int): Boolean = true
        })
        rv.addOnItemTouchListener(listener)
        dragSelect = listener
    }

    fun onExpand(expanded: Boolean) {
        val drawable = callback.actionFabDrawable ?: return
        if (expanded) {
            callback.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT)
            callback.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT)
            drawable.setDelete(ANIMATE_TIME)
        } else {
            callback.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.LEFT)
            callback.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT)
            drawable.setAdd(ANIMATE_TIME)
        }
    }

    /** The primary FAB enters the mode with nothing picked, or leaves it. */
    fun onClickPrimaryFab(view: FabLayout, @Suppress("UNUSED_PARAMETER") fab: FloatingActionButton?) {
        if (callback.mRecyclerView == null) {
            view.toggle()
            return
        }
        if (model.isActive) model.exit() else model.enter()
    }

    fun onItemLongClick(position: Int): Boolean {
        // Tank cards enter multi-select like any row (spec 2026-09-21 §4);
        // batch ops expand them into member rows.
        val key = callback.keyAt(position) ?: return false
        model.toggle(key)
        // The finger is still down: the same touch stream becomes a drag.
        dragSelect?.startDrag(position)
        return true
    }

    /** Click entry point: toggles the row while the mode is on. @return consumed */
    fun toggleChecked(position: Int): Boolean {
        if (!model.isActive) return false
        callback.keyAt(position)?.let(model::toggle)
        return true
    }

    /** Selects [keys] (select all on the page shown). */
    fun selectAll(keys: Collection<String>) = model.selectAll(keys)

    /** Drops the checks of rows the list no longer holds. */
    fun retainAll(keys: Collection<String>) = model.retainAll(keys)

    fun exit() {
        dragSelect?.cancel()
        model.exit()
    }

    private fun render(state: SelectionState) {
        val active = state is SelectionState.Selecting
        if (active != shownActive) {
            shownActive = active
            val rv = callback.mRecyclerView
            if (active) {
                rv?.setOnItemLongClickListener(null)
                rv?.isLongClickable = false
                callback.mFabLayout?.isExpanded = true
                callback.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT)
                callback.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT)
            } else {
                dragSelect?.cancel()
                rv?.setOnItemLongClickListener(callback.longClickListener)
                callback.mFabLayout?.isExpanded = false
                callback.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.LEFT)
                callback.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT)
            }
        }
        callback.mRecyclerView?.refreshChecked()
    }

    companion object {
        private const val ANIMATE_TIME = 300L
    }
}
