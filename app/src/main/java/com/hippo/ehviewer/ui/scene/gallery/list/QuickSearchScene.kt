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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.h6ah4i.android.widget.advrecyclerview.animator.DraggableItemAnimator
import com.h6ah4i.android.widget.advrecyclerview.draggable.DraggableItemAdapter
import com.h6ah4i.android.widget.advrecyclerview.draggable.ItemDraggableRange
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager
import com.h6ah4i.android.widget.advrecyclerview.utils.AbstractDraggableItemViewHolder
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.ehviewer.R
import com.hippo.ehviewer.dao.QuickSearch
import com.hippo.ehviewer.ui.scene.ToolbarScene
import com.hippo.ehviewer.util.collectFlow
import com.hippo.lib.yorozuya.ViewUtils
import com.hippo.util.DrawableManager
import com.hippo.view.ViewTransition

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
    private var mInnerAdapter: QuickSearchAdapter? = null

    /**
     * The snapshot the adapter renders from. The collector advances it and
     * dispatches the DiffUtil delta in the same main-thread step, so adapter
     * state and notifications can never diverge — reading the live StateFlow
     * from getItemCount while notifying manually is what previously primed
     * RecyclerView "inconsistency detected" crashes.
     */
    private var renderedList: List<QuickSearch> = emptyList()

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

        val context = ehContext ?: return view

        val drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_search)
        drawable?.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        tip.setCompoundDrawables(null, drawable, null, null)
        tip.setText(R.string.no_quick_search)

        // drag & drop manager
        val dragDropManager = RecyclerViewDragDropManager()
        dragDropManager.setDraggingItemShadowDrawable(
            AppCompatResources.getDrawable(requireContext(), R.drawable.shadow_8dp) as NinePatchDrawable
        )

        renderedList = emptyList()
        val innerAdapter = QuickSearchAdapter()
        innerAdapter.setHasStableIds(true)
        mInnerAdapter = innerAdapter
        val adapter = dragDropManager.createWrappedAdapter(innerAdapter) // wrap for dragging
        mAdapter = adapter

        val animator = DraggableItemAnimator()
        val recyclerView = mRecyclerView ?: return view
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter
        recyclerView.itemAnimator = animator

        dragDropManager.attachRecyclerView(recyclerView)

        viewModel.loadQuickSearches()
        updateView()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setTitle(R.string.quick_search)
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24)

        // Single notification source: every list change (initial load, delete,
        // failure reconcile) reaches the adapter as a DiffUtil delta against
        // the rendered snapshot. Drag reorders are the one exception — the
        // drag wrapper dispatches its own move, so onMoveItem syncs the
        // snapshot and the matching emission is skipped by identity below.
        collectFlow(viewLifecycleOwner, viewModel.quickSearches) { list ->
            val inner = mInnerAdapter ?: return@collectFlow
            if (list !== renderedList) {
                val old = renderedList
                val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = old.size
                    override fun getNewListSize() = list.size
                    override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                        old[oldPos].id != null && old[oldPos].id == list[newPos].id

                    override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                        old[oldPos].name == list[newPos].name
                })
                renderedList = list
                diff.dispatchUpdatesTo(inner)
            }
            updateView()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        mRecyclerView?.stopScroll()
        mRecyclerView = null
        mInnerAdapter = null
        mAdapter = null

        mViewTransition = null
    }

    override fun onNavigationClick(view: View) {
        onBackPressed()
    }

    private fun updateView() {
        mViewTransition?.let {
            if (renderedList.isNotEmpty()) {
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

            // Adapter positions index the rendered snapshot, not the live flow.
            val quickSearch = renderedList.getOrNull(position) ?: return
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.delete_quick_search_title)
                .setMessage(getString(R.string.delete_quick_search_message, quickSearch.name))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    // No manual notifyItemRemoved: the collector diffs the
                    // snapshot against the updated flow and dispatches the
                    // removal (or, on a DB failure, the reconciled re-insert).
                    viewModel.deleteQuickSearch(quickSearch)
                }
                .show()
        }
    }

    private inner class QuickSearchAdapter :
        RecyclerView.Adapter<QuickSearchHolder>(),
        DraggableItemAdapter<QuickSearchHolder> {

        private val mInflater: LayoutInflater = requireNotNull(layoutInflater2) {
            "layoutInflater2 must not be null"
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QuickSearchHolder {
            return QuickSearchHolder(mInflater.inflate(R.layout.item_quick_search, parent, false))
        }

        override fun onBindViewHolder(holder: QuickSearchHolder, position: Int) {
            renderedList.getOrNull(position)?.let { holder.label.text = it.name }
        }

        override fun getItemId(position: Int): Long {
            return renderedList.getOrNull(position)?.id ?: 0
        }

        override fun getItemCount(): Int {
            return renderedList.size
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
            // The drag wrapper dispatches its own move notification, so sync
            // the rendered snapshot here; the collector skips the matching
            // StateFlow emission by identity instead of double-notifying.
            renderedList = viewModel.quickSearches.value
        }

        override fun onCheckCanDrop(draggingPosition: Int, dropPosition: Int): Boolean = true

        override fun onItemDragStarted(position: Int) {}

        override fun onItemDragFinished(fromPosition: Int, toPosition: Int, result: Boolean) {}
    }

    companion object {
        private const val TAG = "QuickSearchScene"
    }
}
