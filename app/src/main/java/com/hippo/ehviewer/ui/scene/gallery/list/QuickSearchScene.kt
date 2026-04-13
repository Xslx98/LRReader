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

package com.hippo.ehviewer.ui.scene.gallery.list

import android.content.Context
import android.graphics.drawable.NinePatchDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.h6ah4i.android.widget.advrecyclerview.animator.DraggableItemAnimator
import com.h6ah4i.android.widget.advrecyclerview.draggable.DraggableItemAdapter
import com.h6ah4i.android.widget.advrecyclerview.draggable.ItemDraggableRange
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager
import com.h6ah4i.android.widget.advrecyclerview.utils.AbstractDraggableItemViewHolder
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.dao.QuickSearch
import com.hippo.ehviewer.ui.scene.ToolbarScene
import com.hippo.lib.yorozuya.AssertUtils
import com.hippo.lib.yorozuya.ViewUtils
import com.hippo.util.DrawableManager
import com.hippo.view.ViewTransition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuickSearchScene : ToolbarScene() {

    /*---------------
     Whole life cycle
     ---------------*/
    private var mQuickSearchList: MutableList<QuickSearch>? = null

    /*---------------
     View life cycle
     ---------------*/
    private var mRecyclerView: EasyRecyclerView? = null
    private var mViewTransition: ViewTransition? = null
    private var mAdapter: RecyclerView.Adapter<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mQuickSearchList = mutableListOf()
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { EhDB.getAllQuickSearchAsync() }
                mQuickSearchList = result.toMutableList()
                if (result.isNotEmpty()) {
                    mAdapter?.notifyItemRangeInserted(0, result.size)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load quick searches", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mQuickSearchList = null
    }

    override fun onCreateView3(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.scene_label_list, container, false)

        mRecyclerView = ViewUtils.`$$`(view, R.id.recycler_view) as EasyRecyclerView
        val tip = ViewUtils.`$$`(view, R.id.tip) as TextView
        mViewTransition = ViewTransition(mRecyclerView, tip)

        val context = ehContext!!
        AssertUtils.assertNotNull(context)

        val drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_search)
        drawable?.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        tip.setCompoundDrawables(null, drawable, null, null)
        tip.setText(R.string.no_quick_search)

        // drag & drop manager
        val dragDropManager = RecyclerViewDragDropManager()
        dragDropManager.setDraggingItemShadowDrawable(
            AppCompatResources.getDrawable(requireContext(), R.drawable.shadow_8dp) as NinePatchDrawable
        )

        var adapter: RecyclerView.Adapter<*> = QuickSearchAdapter()
        adapter.setHasStableIds(true)
        adapter = dragDropManager.createWrappedAdapter(adapter) // wrap for dragging
        mAdapter = adapter

        val animator = DraggableItemAnimator()
        mRecyclerView!!.layoutManager = LinearLayoutManager(context)
        mRecyclerView!!.adapter = adapter
        mRecyclerView!!.itemAnimator = animator

        dragDropManager.attachRecyclerView(mRecyclerView!!)

        updateView()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setTitle(R.string.quick_search)
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24)
    }

    override fun onDestroyView() {
        super.onDestroyView()

        mRecyclerView?.stopScroll()
        mRecyclerView = null

        mViewTransition = null
    }

    override fun onNavigationClick(view: View) {
        onBackPressed()
    }

    private fun updateView() {
        mViewTransition?.let {
            if (mQuickSearchList != null && mQuickSearchList!!.size > 0) {
                it.showView(0)
            } else {
                it.showView(1)
            }
        }
    }

    private inner class QuickSearchHolder(itemView: View) :
        AbstractDraggableItemViewHolder(itemView), View.OnClickListener {

        val label: TextView = ViewUtils.`$$`(itemView, R.id.label) as TextView
        val dragHandler: View = ViewUtils.`$$`(itemView, R.id.drag_handler)
        val delete: View = ViewUtils.`$$`(itemView, R.id.delete)

        init {
            delete.setOnClickListener(this)
        }

        override fun onClick(v: View) {
            val position = adapterPosition
            val context = ehContext
            if (position == RecyclerView.NO_POSITION || mQuickSearchList == null) {
                return
            }

            val quickSearch = mQuickSearchList!![position]
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.delete_quick_search_title)
                .setMessage(getString(R.string.delete_quick_search_message, quickSearch.name))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    ServiceRegistry.coroutineModule.ioScope.launch {
                        EhDB.deleteQuickSearchAsync(quickSearch)
                    }
                    mQuickSearchList!!.removeAt(position)
                    mAdapter?.notifyItemRemoved(position)
                    updateView()
                }
                .show()
        }
    }

    private inner class QuickSearchAdapter :
        RecyclerView.Adapter<QuickSearchHolder>(),
        DraggableItemAdapter<QuickSearchHolder> {

        private val mInflater: LayoutInflater = layoutInflater2!!.also {
            AssertUtils.assertNotNull(it)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QuickSearchHolder {
            return QuickSearchHolder(mInflater.inflate(R.layout.item_quick_search, parent, false))
        }

        override fun onBindViewHolder(holder: QuickSearchHolder, position: Int) {
            if (mQuickSearchList != null) {
                holder.label.text = mQuickSearchList!![position].name
            }
        }

        override fun getItemId(position: Int): Long {
            return if (mQuickSearchList != null) mQuickSearchList!![position].id ?: 0 else 0
        }

        override fun getItemCount(): Int {
            return mQuickSearchList?.size ?: 0
        }

        override fun onCheckCanStartDrag(holder: QuickSearchHolder, position: Int, x: Int, y: Int): Boolean {
            return ViewUtils.isViewUnder(holder.dragHandler, x, y, 0)
        }

        override fun onGetItemDraggableRange(holder: QuickSearchHolder, position: Int): ItemDraggableRange? {
            return null
        }

        override fun onMoveItem(fromPosition: Int, toPosition: Int) {
            if (fromPosition == toPosition) {
                return
            }
            if (mQuickSearchList == null) {
                return
            }

            ServiceRegistry.coroutineModule.ioScope.launch {
                EhDB.moveQuickSearchAsync(fromPosition, toPosition)
            }
            val item = mQuickSearchList!!.removeAt(fromPosition)
            mQuickSearchList!!.add(toPosition, item)
        }

        override fun onCheckCanDrop(draggingPosition: Int, dropPosition: Int): Boolean = true

        override fun onItemDragStarted(position: Int) {}

        override fun onItemDragFinished(fromPosition: Int, toPosition: Int, result: Boolean) {}
    }

    companion object {
        private const val TAG = "QuickSearchScene"
    }
}
