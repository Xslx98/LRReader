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

import android.graphics.drawable.NinePatchDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.h6ah4i.android.widget.advrecyclerview.animator.DraggableItemAnimator
import com.h6ah4i.android.widget.advrecyclerview.draggable.DraggableItemAdapter
import com.h6ah4i.android.widget.advrecyclerview.draggable.ItemDraggableRange
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager
import com.h6ah4i.android.widget.advrecyclerview.utils.AbstractDraggableItemViewHolder
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.scene.ToolbarScene
import com.hippo.lib.yorozuya.AssertUtils
import com.hippo.lib.yorozuya.ViewUtils
import com.hippo.util.DrawableManager
import com.hippo.view.ViewTransition
import kotlinx.coroutines.launch

class QuickSearchScene : ToolbarScene() {

    /*---------------
     Whole life cycle
     ---------------*/
    private lateinit var viewModel: QuickSearchViewModel

    /*---------------
     View life cycle
     ---------------*/
    private var mRecyclerView: EasyRecyclerView? = null
    private var mViewTransition: ViewTransition? = null
    private var mAdapter: RecyclerView.Adapter<*>? = null

    override fun onCreateView3(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel = ViewModelProvider(requireActivity())[QuickSearchViewModel::class.java]

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

        // Observe ViewModel quick search list for initial load
        lifecycleScope.launch {
            var previousSize = 0
            viewModel.quickSearches.collect { list ->
                val adapterRef = mAdapter ?: return@collect
                if (previousSize == 0 && list.isNotEmpty()) {
                    adapterRef.notifyItemRangeInserted(0, list.size)
                }
                previousSize = list.size
                updateView()
            }
        }

        viewModel.loadQuickSearches()
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
            val list = viewModel.quickSearches.value
            if (list.isNotEmpty()) {
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
            if (position == RecyclerView.NO_POSITION) {
                return
            }

            val list = viewModel.quickSearches.value
            if (position >= list.size) return

            val quickSearch = list[position]
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.delete_quick_search_title)
                .setMessage(getString(R.string.delete_quick_search_message, quickSearch.name))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewModel.deleteQuickSearch(quickSearch)
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
            val list = viewModel.quickSearches.value
            if (position < list.size) {
                holder.label.text = list[position].name
            }
        }

        override fun getItemId(position: Int): Long {
            val list = viewModel.quickSearches.value
            return if (position < list.size) list[position].id ?: 0 else 0
        }

        override fun getItemCount(): Int {
            return viewModel.quickSearches.value.size
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
            viewModel.moveQuickSearch(fromPosition, toPosition)
        }

        override fun onCheckCanDrop(draggingPosition: Int, dropPosition: Int): Boolean = true

        override fun onItemDragStarted(position: Int) {}

        override fun onItemDragFinished(fromPosition: Int, toPosition: Int, result: Boolean) {}
    }

    companion object {
        private const val TAG = "QuickSearchScene"
    }
}
