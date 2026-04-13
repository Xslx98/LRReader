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
package com.hippo.ehviewer.ui.scene.download

import android.view.Gravity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.hippo.drawable.AddDeleteDrawable
import com.hippo.drawerlayout.DrawerLayout
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.widget.FabLayout

/**
 * Manages custom choice/selection mode, FAB expand/collapse, and drawer
 * locking state for [DownloadsScene]. Extracted in W16-1.
 */
internal class DownloadSelectionHelper(private val callback: Callback) {

    interface Callback {
        val mRecyclerView: EasyRecyclerView?
        val mFabLayout: FabLayout?
        val actionFabDrawable: AddDeleteDrawable?
        val longClickListener: EasyRecyclerView.OnItemLongClickListener
        fun setDrawerLockMode(lockMode: Int, gravity: Int)
    }

    /** [EasyRecyclerView.CustomChoiceListener] that bridges into this helper. */
    val choiceListener: EasyRecyclerView.CustomChoiceListener = ChoiceListenerImpl()

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

    fun onClickPrimaryFab(view: FabLayout, @Suppress("UNUSED_PARAMETER") fab: FloatingActionButton?) {
        val recyclerView = callback.mRecyclerView
        if (recyclerView != null && recyclerView.isInCustomChoice) {
            recyclerView.outOfCustomChoiceMode()
            return
        }
        if (recyclerView != null && !recyclerView.isInCustomChoice) {
            recyclerView.intoCustomChoiceMode()
            return
        }
        view.toggle()
    }

    fun onItemLongClick(position: Int): Boolean {
        val recyclerView = callback.mRecyclerView ?: return false
        if (!recyclerView.isInCustomChoice) {
            recyclerView.intoCustomChoiceMode()
        }
        recyclerView.toggleItemChecked(position)
        return true
    }

    private inner class ChoiceListenerImpl : EasyRecyclerView.CustomChoiceListener {
        override fun onIntoCustomChoice(view: EasyRecyclerView) {
            callback.mRecyclerView?.let {
                it.setOnItemLongClickListener(null)
                it.isLongClickable = false
            }
            callback.mFabLayout?.setExpanded(true)
            callback.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT)
            callback.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT)
        }

        override fun onOutOfCustomChoice(view: EasyRecyclerView) {
            callback.mRecyclerView?.setOnItemLongClickListener(callback.longClickListener)
            callback.mFabLayout?.setExpanded(false)
            callback.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.LEFT)
            callback.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT)
        }

        override fun onItemCheckedStateChanged(view: EasyRecyclerView, position: Int, id: Long, checked: Boolean) {
            if (view.checkedItemCount == 0) {
                view.outOfCustomChoiceMode()
            }
        }
    }

    companion object {
        private const val ANIMATE_TIME = 300L
    }
}
