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

import android.content.DialogInterface
import android.graphics.drawable.NinePatchDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.h6ah4i.android.widget.advrecyclerview.animator.SwipeDismissItemAnimator
import com.h6ah4i.android.widget.advrecyclerview.draggable.DraggableItemAdapter
import com.h6ah4i.android.widget.advrecyclerview.draggable.ItemDraggableRange
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager
import com.h6ah4i.android.widget.advrecyclerview.utils.AbstractDraggableItemViewHolder
import androidx.lifecycle.ViewModelProvider
import com.hippo.app.EditTextDialogBuilder
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.ehviewer.R
import com.hippo.ehviewer.dao.DownloadLabel
import com.hippo.ehviewer.ui.scene.ToolbarScene
import com.hippo.util.DrawableManager
import com.hippo.view.ViewTransition
import com.hippo.lib.yorozuya.AssertUtils
import com.hippo.lib.yorozuya.ViewUtils

class DownloadLabelsScene : ToolbarScene() {

    /*---------------
     Whole life cycle
     ---------------*/
    private lateinit var viewModel: DownloadLabelsViewModel

    @JvmField
    var mList: List<DownloadLabel>? = null

    /*---------------
     View life cycle
     ---------------*/
    private var mRecyclerView: EasyRecyclerView? = null
    private var mViewTransition: ViewTransition? = null
    private var mAdapter: RecyclerView.Adapter<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[DownloadLabelsViewModel::class.java]
        mList = viewModel.labels
    }

    override fun onDestroy() {
        super.onDestroy()
        mList = null
    }

    override fun onCreateView3(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.scene_label_list, container, false)

        val recyclerView = ViewUtils.`$$`(view, R.id.recycler_view) as EasyRecyclerView
        mRecyclerView = recyclerView
        val tip = ViewUtils.`$$`(view, R.id.tip) as TextView
        mViewTransition = ViewTransition(recyclerView, tip)

        val context = ehContext ?: return view
        val drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_label)
        drawable?.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        tip.setCompoundDrawables(null, drawable, null, null)
        tip.setText(R.string.no_download_label)

        // drag & drop manager
        val dragDropManager = RecyclerViewDragDropManager()
        dragDropManager.setDraggingItemShadowDrawable(
            androidx.appcompat.content.res.AppCompatResources.getDrawable(
                requireContext(), R.drawable.shadow_8dp
            ) as NinePatchDrawable
        )

        var adapter: RecyclerView.Adapter<*> = LabelAdapter()
        adapter.setHasStableIds(true)
        adapter = dragDropManager.createWrappedAdapter(adapter) // wrap for dragging
        mAdapter = adapter
        val animator = SwipeDismissItemAnimator()

        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter
        recyclerView.itemAnimator = animator

        dragDropManager.attachRecyclerView(recyclerView)

        updateView()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setTitle(R.string.download_labels)
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24)
    }

    override fun onDestroyView() {
        super.onDestroyView()

        mRecyclerView?.stopScroll()
        mRecyclerView = null

        mViewTransition = null
        mAdapter = null
    }

    override fun onNavigationClick(view: View) {
        onBackPressed()
    }

    override fun getMenuResId(): Int {
        return R.menu.scene_download_label
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        val context = ehContext ?: return false

        when (item.itemId) {
            R.id.action_add -> {
                val builder = EditTextDialogBuilder(
                    context, null, getString(R.string.download_labels)
                )
                builder.setTitle(R.string.new_label_title)
                builder.setPositiveButton(android.R.string.ok, null)
                val dialog = builder.show()
                NewLabelDialogHelper(builder, dialog)
            }
        }
        return false
    }

    private fun updateView() {
        mViewTransition?.let { vt ->
            if (mList?.isNotEmpty() == true) {
                vt.showView(0)
            } else {
                vt.showView(1)
            }
        }
    }

    private inner class NewLabelDialogHelper(
        private val mBuilder: EditTextDialogBuilder,
        private val mDialog: AlertDialog
    ) : View.OnClickListener {

        init {
            mDialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener(this)
        }

        override fun onClick(v: View) {
            val context = ehContext ?: return

            val text = mBuilder.text
            if (TextUtils.isEmpty(text)) {
                mBuilder.setError(getString(R.string.label_text_is_empty))
            } else if (getString(R.string.default_download_label_name) == text) {
                mBuilder.setError(getString(R.string.label_text_is_invalid))
            } else if (viewModel.containsLabel(text)) {
                mBuilder.setError(getString(R.string.label_text_exist))
            } else {
                mBuilder.setError(null)
                mDialog.dismiss()
                viewModel.addLabel(text)
                val list = mList
                if (mAdapter != null && list != null) {
                    mAdapter?.notifyItemInserted(list.size - 1)
                }
                mViewTransition?.let { vt ->
                    if (mList?.isNotEmpty() == true) {
                        vt.showView(0)
                    } else {
                        vt.showView(1)
                    }
                }
            }
        }
    }

    private inner class RenameLabelDialogHelper(
        private val mBuilder: EditTextDialogBuilder,
        private val mDialog: AlertDialog,
        private val mOriginalLabel: String,
        private val mPosition: Int
    ) : View.OnClickListener {

        init {
            mDialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener(this)
        }

        override fun onClick(v: View) {
            val context = ehContext ?: return

            val text = mBuilder.text
            if (TextUtils.isEmpty(text)) {
                mBuilder.setError(getString(R.string.label_text_is_empty))
            } else if (getString(R.string.default_download_label_name) == text) {
                mBuilder.setError(getString(R.string.label_text_is_invalid))
            } else if (viewModel.containsLabel(text)) {
                mBuilder.setError(getString(R.string.label_text_exist))
            } else {
                mBuilder.setError(null)
                mDialog.dismiss()
                viewModel.renameLabel(mOriginalLabel, text)
                mAdapter?.notifyItemChanged(mPosition)
            }
        }
    }

    private inner class LabelHolder(itemView: View) :
        AbstractDraggableItemViewHolder(itemView), View.OnClickListener {

        val label: TextView = ViewUtils.`$$`(itemView, R.id.label) as TextView
        val dragHandler: View = ViewUtils.`$$`(itemView, R.id.drag_handler)
        val delete: View = ViewUtils.`$$`(itemView, R.id.delete)

        init {
            label.setOnClickListener(this)
            delete.setOnClickListener(this)
        }

        override fun onClick(v: View) {
            val position = adapterPosition
            val context = ehContext
            val list = mList
            if (context == null || list == null || mRecyclerView == null) {
                return
            }

            if (label === v) {
                val raw = list[position]
                val builder = EditTextDialogBuilder(
                    context, raw.label, getString(R.string.download_labels)
                )
                builder.setTitle(R.string.rename_label_title)
                builder.setPositiveButton(android.R.string.ok, null)
                val dialog = builder.show()
                RenameLabelDialogHelper(builder, dialog, raw.label ?: "", position)
            } else if (delete === v) {
                val downloadLabel = list[position]
                AlertDialog.Builder(context)
                    .setTitle(R.string.delete_label_title)
                    .setMessage(getString(R.string.delete_label_message, downloadLabel.label))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        // DownloadManager.deleteLabel() mutates mLabelList synchronously
                        // on the main thread (DB persist is fire-and-forget). So by the
                        // time this lambda returns, mList.size is already smaller and
                        // notifyItemRemoved(position) is the precise notification to
                        // dispatch. Doing this in the positive button callback (rather
                        // than OnDismissListener) also fixes a pre-existing bug where
                        // a Cancel/back-button dismiss would still fire notifyDataSetChanged.
                        viewModel.deleteLabel(downloadLabel.label ?: "")
                        mAdapter?.notifyItemRemoved(position)
                        updateView()
                    }
                    .show()
            }
        }
    }

    private inner class LabelAdapter : RecyclerView.Adapter<LabelHolder>(),
        DraggableItemAdapter<LabelHolder> {

        private val mInflater: LayoutInflater = layoutInflater2 ?: LayoutInflater.from(requireContext())

        init {
            AssertUtils.assertNotNull(mInflater)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LabelHolder {
            return LabelHolder(mInflater.inflate(R.layout.item_download_label, parent, false))
        }

        override fun onBindViewHolder(holder: LabelHolder, position: Int) {
            mList?.let { list ->
                holder.label.text = list[position].label
            }
        }

        override fun getItemId(position: Int): Long {
            return mList?.get(position)?.id ?: 0L
        }

        override fun getItemCount(): Int {
            return mList?.size ?: 0
        }

        override fun onCheckCanStartDrag(holder: LabelHolder, position: Int, x: Int, y: Int): Boolean {
            return ViewUtils.isViewUnder(holder.dragHandler, x, y, 0)
        }

        override fun onGetItemDraggableRange(holder: LabelHolder, position: Int): ItemDraggableRange? {
            return null
        }

        override fun onMoveItem(fromPosition: Int, toPosition: Int) {
            val context = ehContext
            if (context == null || fromPosition == toPosition) {
                return
            }
            viewModel.moveLabel(fromPosition, toPosition)
        }

        override fun onCheckCanDrop(draggingPosition: Int, dropPosition: Int): Boolean {
            return true
        }

        override fun onItemDragStarted(position: Int) {}

        override fun onItemDragFinished(fromPosition: Int, toPosition: Int, result: Boolean) {}
    }
}
