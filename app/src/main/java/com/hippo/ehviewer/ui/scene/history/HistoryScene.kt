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

package com.hippo.ehviewer.ui.scene.history

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.graphics.Color
import androidx.core.graphics.drawable.toDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.h6ah4i.android.widget.advrecyclerview.animator.SwipeDismissItemAnimator
import com.h6ah4i.android.widget.advrecyclerview.swipeable.RecyclerViewSwipeManager
import com.h6ah4i.android.widget.advrecyclerview.swipeable.SwipeableItemAdapter
import com.h6ah4i.android.widget.advrecyclerview.swipeable.SwipeableItemConstants
import com.h6ah4i.android.widget.advrecyclerview.swipeable.action.SwipeResultAction
import com.h6ah4i.android.widget.advrecyclerview.swipeable.action.SwipeResultActionDefault
import com.h6ah4i.android.widget.advrecyclerview.swipeable.action.SwipeResultActionRemoveItem
import com.h6ah4i.android.widget.advrecyclerview.touchguard.RecyclerViewTouchActionGuardManager
import com.h6ah4i.android.widget.advrecyclerview.utils.AbstractSwipeableItemViewHolder
import com.hippo.android.resource.AttrResources
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.easyrecyclerview.FastScroller
import com.hippo.easyrecyclerview.HandlerDrawable
import com.hippo.easyrecyclerview.MarginItemDecoration
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.LRRCacheKeyFactory
import com.hippo.ehviewer.client.LRRUtils
import com.hippo.ehviewer.gallery.ReadingContext
import com.hippo.ehviewer.gallery.ReadingContextStore
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.ui.scene.ListMultiSelectHelper
import com.hippo.ehviewer.ui.scene.ToolbarScene
import com.hippo.ehviewer.ui.scene.TransitionNameFactory
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene
import com.hippo.ehviewer.ui.scene.gallery.list.EnterGalleryDetailTransaction
import com.hippo.ehviewer.util.collectFlow
import com.hippo.ehviewer.widget.SimpleRatingView
import com.hippo.ripple.Ripple
import com.hippo.scene.Announcer
import com.hippo.util.DrawableManager
import com.hippo.view.ViewTransition
import com.hippo.widget.LoadImageView
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager
import com.hippo.lib.yorozuya.AssertUtils
import com.hippo.lib.yorozuya.ViewUtils
import com.lanraragi.reader.domain.Archive
import kotlinx.coroutines.launch

class HistoryScene : ToolbarScene(),
    EasyRecyclerView.OnItemClickListener,
    EasyRecyclerView.OnItemLongClickListener {

    /*---------------
     ViewModel
     ---------------*/
    private lateinit var viewModel: HistoryViewModel

    /*---------------
     View life cycle
     ---------------*/
    private lateinit var mRecyclerView: EasyRecyclerView
    private var mViewTransition: ViewTransition? = null
    private var mAdapter: RecyclerView.Adapter<*>? = null
    private lateinit var mLayoutManager: AutoStaggeredGridLayoutManager

    /*---------------
     Multi-select
     ---------------*/
    private var multiSelectHelper: ListMultiSelectHelper? = null
    private var batchBar: View? = null
    private var batchCountView: TextView? = null

    override fun getNavCheckedItem(): Int {
        return R.id.nav_history
    }

    override fun onCreateView3(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        viewModel = ViewModelProvider(requireActivity())[HistoryViewModel::class.java]

        val view = inflater.inflate(R.layout.scene_history, container, false)
        val content = ViewUtils.`$$`(view, R.id.content)
        mRecyclerView = ViewUtils.`$$`(content, R.id.recycler_view) as EasyRecyclerView
        val fastScroller = ViewUtils.`$$`(content, R.id.fast_scroller) as FastScroller
        val tip = ViewUtils.`$$`(view, R.id.tip) as TextView
        mViewTransition = ViewTransition(content, tip)

        val context = ehContext
        AssertUtils.assertNotNull(context)
        val resources = context!!.resources

        val drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_history)
        drawable?.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        tip.setCompoundDrawables(null, drawable, null, null)

        val guardManager = RecyclerViewTouchActionGuardManager()
        guardManager.setInterceptVerticalScrollingWhileAnimationRunning(true)
        guardManager.isEnabled = true
        val swipeManager = RecyclerViewSwipeManager()
        var adapter: RecyclerView.Adapter<*> = HistoryAdapter()
        adapter.setHasStableIds(true)
        adapter = swipeManager.createWrappedAdapter(adapter)
        mAdapter = adapter
        mRecyclerView.adapter = mAdapter
        val animator = SwipeDismissItemAnimator()
        animator.supportsChangeAnimations = false
        mRecyclerView.itemAnimator = animator
        mLayoutManager = AutoStaggeredGridLayoutManager(
            0, StaggeredGridLayoutManager.VERTICAL
        )
        mLayoutManager.setColumnSize(
            resources.getDimensionPixelOffset(AppearanceSettings.getDetailSizeResId())
        )
        mLayoutManager.setStrategy(AutoStaggeredGridLayoutManager.STRATEGY_MIN_SIZE)
        mRecyclerView.layoutManager = mLayoutManager
        mRecyclerView.setSelector(
            Ripple.generateRippleDrawable(
                context,
                !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme),
                Color.TRANSPARENT.toDrawable()
            )
        )
        mRecyclerView.setDrawSelectorOnTop(true)
        mRecyclerView.clipToPadding = false
        mRecyclerView.setOnItemClickListener(this)
        mRecyclerView.setOnItemLongClickListener(this)

        // Bottom batch action bar (shared widget). History supports a single
        // batch op — remove-from-history — so the gallery-specific buttons are
        // hidden and the delete slot is repurposed with the remove label.
        val bar = ViewUtils.`$$`(view, R.id.batch_action_bar)
        batchBar = bar
        batchCountView = bar.findViewById(R.id.batch_count)
        bar.findViewById<Button>(R.id.batch_download).visibility = View.GONE
        bar.findViewById<Button>(R.id.batch_category).visibility = View.GONE
        bar.findViewById<Button>(R.id.batch_tankoubon).visibility = View.GONE
        bar.findViewById<Button>(R.id.batch_clear_new).visibility = View.GONE
        bar.findViewById<Button>(R.id.batch_select_all)
            .setOnClickListener { multiSelectHelper?.checkAll() }
        bar.findViewById<Button>(R.id.batch_delete).apply {
            setText(R.string.batch_remove_history)
            setOnClickListener { onBatchRemoveClick() }
        }
        val multiSelect = ListMultiSelectHelper(
            recyclerView = { if (::mRecyclerView.isInitialized) mRecyclerView else null },
            longClickListener = { this },
            onModeChanged = { active ->
                batchBar?.visibility = if (active) View.VISIBLE else View.GONE
            },
            onCheckedChanged = { count ->
                batchCountView?.let {
                    it.text = it.context.getString(R.string.batch_selected_count, count)
                }
            },
        )
        multiSelectHelper = multiSelect
        // intoCustomChoiceMode() is a no-op unless the custom choice mode is
        // configured, and it dispatches to the listener without a null check —
        // both calls below are required before the mode can be entered.
        mRecyclerView.setChoiceMode(EasyRecyclerView.CHOICE_MODE_MULTIPLE_CUSTOM)
        mRecyclerView.setCustomCheckedListener(multiSelect.choiceListener)

        val interval = resources.getDimensionPixelOffset(R.dimen.gallery_list_interval)
        val paddingH = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_h)
        val paddingV = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_v)
        val decoration = MarginItemDecoration(interval, paddingH, paddingV, paddingH, paddingV)
        mRecyclerView.addItemDecoration(decoration)
        decoration.applyPaddings(mRecyclerView)
        guardManager.attachRecyclerView(mRecyclerView)
        swipeManager.attachRecyclerView(mRecyclerView)

        fastScroller.attachToRecyclerView(mRecyclerView)
        val handlerDrawable = HandlerDrawable()
        handlerDrawable.setColor(AttrResources.getAttrColor(context, R.attr.widgetColorThemeAccent))
        fastScroller.setHandlerDrawable(handlerDrawable)

        // Observe ViewModel list updates for DiffUtil dispatch. Scope to the *view*
        // lifecycle, not the fragment: onCreateView3 re-runs on every detach/re-attach,
        // so a fragment-scoped collector would accumulate across navigations and dispatch
        // the same DiffResult N times — corrupting the RecyclerView on structural diffs.
        viewLifecycleOwner.lifecycleScope.launch(ServiceRegistry.coroutineModule.exceptionHandler) {
            viewModel.listUpdate.collect { update ->
                val adapterRef = mAdapter ?: return@collect
                update.diffResult.dispatchUpdatesTo(adapterRef)
                updateView(false)
            }
        }

        viewModel.loadHistory()
        updateView(false)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setTitle(R.string.history)
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24)
        // View-scoped so a re-created view does not stack duplicate collectors.
        collectFlow(viewLifecycleOwner, viewModel.batchRemoveDone) { (ok, bad) ->
            showTip(getString(R.string.batch_result_summary, ok, bad), LENGTH_SHORT)
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh column size to pick up detail_size changes from settings
        if (::mLayoutManager.isInitialized) {
            val columnWidth = resources.getDimensionPixelOffset(AppearanceSettings.getDetailSizeResId())
            mLayoutManager.setColumnSize(columnWidth)
            mRecyclerView.requestLayout()
        }
        // Reload history to pick up rating changes made in detail page.
        // The reload replaces the list and invalidates adapter positions, so
        // any in-flight multi-select must not survive it.
        multiSelectHelper?.exit()
        viewModel.loadHistory()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        if (viewModel.historyList.value.isNotEmpty()) {
            // Adapter is being torn down with this view; a structural notify is
            // required so the framework drops cached ViewHolders that referenced
            // the history list. notifyDataSetChanged is acceptable here because
            // there is no concurrent dispatch path during view destruction.
            @Suppress("NotifyDataSetChanged")
            mAdapter?.notifyDataSetChanged()
        }
        // Reset snapshot so the next onCreateView starts from an empty baseline
        // and the first loadHistory() dispatch is a clean inserts-only delta.
        viewModel.resetSnapshot()
        mRecyclerView.stopScroll()

        mViewTransition = null
        mAdapter = null
        multiSelectHelper = null
        batchBar = null
        batchCountView = null
    }

    override fun onBackPressed() {
        // Back leaves multi-select mode before leaving the scene.
        val multiSelect = multiSelectHelper
        if (multiSelect != null && multiSelect.isActive) {
            multiSelect.exit()
            return
        }
        super.onBackPressed()
    }

    private fun updateView(animation: Boolean) {
        val adapter = mAdapter ?: return
        val viewTransition = mViewTransition ?: return

        if (adapter.itemCount == 0) {
            viewTransition.showView(1, animation)
        } else {
            viewTransition.showView(0, animation)
        }
    }

    override fun onNavigationClick(view: View) {
        onBackPressed()
    }

    override fun getMenuResId(): Int {
        return R.menu.scene_history
    }

    private fun showClearAllDialog() {
        AlertDialog.Builder(ehContext!!)
            .setMessage(R.string.clear_all_history)
            .setPositiveButton(R.string.clear_all) { _, which ->
                if (DialogInterface.BUTTON_POSITIVE != which || mAdapter == null) {
                    return@setPositiveButton
                }
                // The list is about to empty out — checked positions become
                // meaningless, so leave multi-select first.
                multiSelectHelper?.exit()
                viewModel.clearAllHistory()
            }.show()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        // Skip when in choice mode
        val context = ehContext ?: return false

        val id = item.itemId
        when (id) {
            R.id.action_clear_all -> {
                showClearAllDialog()
                return true
            }
        }
        return false
    }

    override fun onItemClick(parent: EasyRecyclerView, view: View, position: Int, id: Long): Boolean {
        // In multi-select mode a click toggles the row instead of navigating —
        // the library does not toggle on click by itself (mirrors gallery list).
        multiSelectHelper?.let { if (it.isActive) return it.toggleChecked(position) }
        val list = viewModel.historyList.value
        if (position >= list.size) return false
        val archive = list[position]

        ReadingContextStore.publish(
            ReadingContext.LocalList(
                kind = ReadingContext.LocalList.Kind.HISTORY,
                forwardArchives = list.subList(position, minOf(list.size, position + ReadingContextStore.LOCAL_WINDOW)).toList(),
                anchorArcid = archive.arcid,
            )
        )

        val args = Bundle()
        args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_ARCHIVE)
        args.putParcelable(GalleryDetailScene.KEY_ARCHIVE, archive)
        val announcer = Announcer(GalleryDetailScene::class.java).setArgs(args)
            .setRequestCode(this, REQUEST_CODE_GALLERY_DETAIL)
        val thumb = view.findViewById<View>(R.id.thumb)
        if (thumb != null) {
            announcer.setTranHelper(EnterGalleryDetailTransaction(thumb))
        }
        startScene(announcer)
        return true
    }

    override fun onSceneResult(requestCode: Int, resultCode: Int, data: Bundle?) {
        if (requestCode == REQUEST_CODE_GALLERY_DETAIL
            && resultCode == RESULT_OK && data != null
        ) {
            val arcid = data.getString(GalleryDetailScene.KEY_ARCID)
            val rating = data.getFloat(GalleryDetailScene.KEY_RATING_RESULT, Float.NaN)
            if (arcid != null && !rating.isNaN()) {
                // Update the Archive display list in-place
                val list = viewModel.historyList.value
                for (i in list.indices) {
                    if (list[i].arcid == arcid) {
                        viewModel.updateRatingAtPosition(i, rating)
                        mAdapter?.notifyItemChanged(i)
                        break
                    }
                }
            }
        }
        super.onSceneResult(requestCode, resultCode, data)
    }

    override fun onItemLongClick(parent: EasyRecyclerView, view: View, position: Int, id: Long): Boolean =
        multiSelectHelper?.enterAndCheck(position) ?: false

    /**
     * Confirm-then-remove for the current selection. The selection is captured
     * before the dialog (it cannot change under a modal dialog, but the exit
     * below reorders the list) and multi-select is left only on confirm —
     * cancelling keeps the selection, matching the gallery-list batch delete.
     */
    private fun onBatchRemoveClick() {
        val context = ehContext ?: return
        val multiSelect = multiSelectHelper ?: return
        val list = viewModel.historyList.value
        val selected: List<Archive> = multiSelect.checkedPositions().mapNotNull { list.getOrNull(it) }
        if (selected.isEmpty()) return
        AlertDialog.Builder(context)
            .setMessage(context.getString(R.string.batch_remove_history_confirm, selected.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.batch_remove_history) { _, _ ->
                // Exit before the removal: rows fall out and positions shift.
                multiSelect.exit()
                viewModel.removeHistories(selected)
            }
            .show()
    }

    private inner class HistoryHolder(itemView: View) : AbstractSwipeableItemViewHolder(itemView) {

        val card: View = itemView.findViewById(R.id.card)
        val thumb: LoadImageView = itemView.findViewById(R.id.thumb)
        val title: TextView = itemView.findViewById(R.id.title)
        val uploader: TextView = itemView.findViewById(R.id.uploader)
        val rating: SimpleRatingView = itemView.findViewById(R.id.rating)
        val category: TextView = itemView.findViewById(R.id.category)
        val posted: TextView = itemView.findViewById(R.id.posted)
        val simpleLanguage: TextView = itemView.findViewById(R.id.simple_language)

        override fun getSwipeableContainerView(): View {
            return card
        }
    }

    // InflateParams: the init-block calculator view is measure-only, never attached.
    @SuppressLint("InflateParams")
    private inner class HistoryAdapter :
        RecyclerView.Adapter<HistoryHolder>(),
        SwipeableItemAdapter<HistoryHolder> {

        private val mInflater: LayoutInflater = layoutInflater2
        private val mListThumbWidth: Int
        private val mListThumbHeight: Int

        init {
            val calculator = mInflater.inflate(R.layout.item_gallery_list_thumb_height, null)
            ViewUtils.measureView(calculator, 1024, ViewGroup.LayoutParams.WRAP_CONTENT)
            mListThumbHeight = calculator.measuredHeight
            mListThumbWidth = mListThumbHeight * 2 / 3
        }

        override fun getItemId(position: Int): Long {
            val list = viewModel.historyList.value
            return if (position < list.size) list[position].arcid.hashCode().toLong() else super.getItemId(position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryHolder {
            val holder = HistoryHolder(mInflater.inflate(R.layout.item_history, parent, false))

            val lp = holder.thumb.layoutParams
            lp.width = mListThumbWidth
            lp.height = mListThumbHeight
            holder.thumb.layoutParams = lp

            return holder
        }

        override fun onBindViewHolder(holder: HistoryHolder, position: Int) {
            val list = viewModel.historyList.value
            if (position >= list.size) return

            val archive = list[position]
            holder.thumb.load(LRRCacheKeyFactory.getThumbKey(archive.arcid), archive.thumbnailUrl)
            holder.title.text = archive.title
            holder.uploader.text = null
            holder.rating.setRating(archive.rating)
            val category = holder.category
            val newCategoryText = LRRUtils.getCategory(-1)
            if (newCategoryText != category.text.toString()) {
                category.text = newCategoryText
                category.setBackgroundColor(LRRUtils.getCategoryColor(-1))
            }
            holder.posted.text = null
            holder.simpleLanguage.text = null

            // Update transition name
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ViewCompat.setTransitionName(
                    holder.thumb,
                    TransitionNameFactory.getThumbTransitionName(archive.arcid)
                )
            }
        }

        override fun getItemCount(): Int {
            return viewModel.historyList.value.size
        }

        override fun onGetSwipeReactionType(holder: HistoryHolder, position: Int, x: Int, y: Int): Int {
            // Swiping a row away during multi-select would remove it and shift
            // every later position while the checked SparseArray does not remap.
            if (multiSelectHelper?.isActive == true) {
                return SwipeableItemConstants.REACTION_CAN_NOT_SWIPE_ANY
            }
            return SwipeableItemConstants.REACTION_CAN_SWIPE_LEFT
        }

        override fun onSwipeItemStarted(holder: HistoryHolder, position: Int) {}

        override fun onSetSwipeBackground(holder: HistoryHolder, position: Int, type: Int) {}

        override fun onSwipeItem(holder: HistoryHolder, position: Int, result: Int): SwipeResultAction {
            return when (result) {
                SwipeableItemConstants.RESULT_SWIPED_LEFT -> SwipeResultActionClear(position)
                else -> SwipeResultActionDefault()
            }
        }
    }

    private inner class SwipeResultActionClear(
        private val mPosition: Int
    ) : SwipeResultActionRemoveItem() {

        override fun onPerformAction() {
            super.onPerformAction()
            if (mAdapter == null) return
            val info = viewModel.getRawHistoryInfo(mPosition) ?: return

            viewModel.deleteHistoryItem(info)
        }
    }

    companion object {
        private const val REQUEST_CODE_GALLERY_DETAIL = 200
    }
}
