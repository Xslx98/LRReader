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

package com.lanraragi.reader.ui.scene.history

import com.lanraragi.reader.ui.Haptics
import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.ui.collectFlowWhileCreated
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
import com.lanraragi.framework.util.attrColor
import com.lanraragi.framework.util.isLightTheme
import com.lanraragi.reader.widget.list.EasyRecyclerView
import com.lanraragi.reader.widget.list.FastScroller
import com.lanraragi.reader.widget.list.HandlerDrawable
import com.lanraragi.reader.widget.list.MarginItemDecoration
import com.lanraragi.reader.R
import com.lanraragi.reader.cover.ArchiveCoverStamps
import com.lanraragi.reader.cover.LRRCacheKeyFactory
import com.lanraragi.reader.archive.LRRUtils
import com.lanraragi.reader.cover.TankCoverCacheStamp
import com.lanraragi.reader.reading.ReadingContext
import com.lanraragi.reader.reading.ReadingContextStore
import com.lanraragi.reader.reading.HistoryTankRedirect
import com.lanraragi.reader.reading.TankSessionRouter
import com.lanraragi.reader.settings.AppearanceSettings
import com.lanraragi.reader.ui.scene.BatchBarAnimator
import com.lanraragi.reader.ui.scene.ListMultiSelectHelper
import com.lanraragi.reader.ui.scene.ToolbarScene
import com.lanraragi.reader.ui.scene.TransitionNameFactory
import com.lanraragi.reader.ui.scene.gallery.detail.GalleryDetailScene
import com.lanraragi.reader.ui.scene.gallery.list.EnterGalleryDetailTransaction
import com.lanraragi.reader.ui.collectFlow
import com.lanraragi.reader.widget.SimpleRatingView
import com.lanraragi.reader.widget.StateListLayout
import com.lanraragi.reader.widget.Ripples
import com.lanraragi.framework.scene.Announcer
import androidx.core.content.ContextCompat
import com.lanraragi.reader.widget.LoadImageView
import com.lanraragi.framework.widget.recyclerview.AutoStaggeredGridLayoutManager
import com.lanraragi.framework.util.AssertUtils
import com.lanraragi.framework.util.ViewUtils
import com.lanraragi.reader.client.api.friendlyError
import com.lanraragi.reader.client.api.isTankoubonId
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.ui.GalleryOpenHelper
import com.lanraragi.reader.ui.list.ListDiffBinder
import com.lanraragi.reader.ui.list.ListScreenState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var mStateList: StateListLayout? = null

    // What the adapter shows, fed only by the state collector. See ListDiffBinder.
    private val mRows = ListDiffBinder<HistoryViewModel.HistoryRow> { old, new ->
        HistoryViewModel.RowDiffCallback(old, new)
    }

    /** The archive shown at adapter [position], or null past the end. */
    private fun archiveAt(position: Int) = mRows.items.getOrNull(position)?.archive
    private var mAdapter: RecyclerView.Adapter<*>? = null

    /** [TankCoverCacheStamp] value the tank pseudo-rows were last bound with. */
    private var mTankCoverStampSeen = TankCoverCacheStamp.generation
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
        val view = inflater.inflate(R.layout.scene_history, container, false)
        mStateList = (view as StateListLayout).apply { onRetry = { viewModel.loadHistory() } }
        val content = ViewUtils.requireView(view, R.id.state_content)
        mRecyclerView = ViewUtils.requireView(content, R.id.recycler_view) as EasyRecyclerView
        val fastScroller = ViewUtils.requireView(content, R.id.fast_scroller) as FastScroller
        val tip = ViewUtils.requireView(view, R.id.state_empty) as TextView

        val context = ehContext
        AssertUtils.assertNotNull(context)
        val resources = context!!.resources

        val drawable = ContextCompat.getDrawable(context, R.drawable.big_history)
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
        mRows.adapter = adapter
        mRecyclerView.adapter = mAdapter
        val animator = SwipeDismissItemAnimator()
        animator.supportsChangeAnimations = false
        mRecyclerView.itemAnimator = animator
        mLayoutManager = AutoStaggeredGridLayoutManager(
            0, StaggeredGridLayoutManager.VERTICAL
        )
        mLayoutManager.columnSize = resources.getDimensionPixelOffset(AppearanceSettings.getDetailSizeResId())
        mLayoutManager.strategy = AutoStaggeredGridLayoutManager.STRATEGY_MIN_SIZE
        mRecyclerView.layoutManager = mLayoutManager
        mRecyclerView.selector = Ripples.rippleDrawable(
            !context.isLightTheme(),
            Color.TRANSPARENT.toDrawable()
        )
        mRecyclerView.setDrawSelectorOnTop(true)
        mRecyclerView.clipToPadding = false
        mRecyclerView.setOnItemClickListener(this)
        mRecyclerView.setOnItemLongClickListener(this)

        // Bottom batch action bar (shared widget). History supports a single
        // batch op — remove-from-history — so the gallery-specific buttons are
        // hidden and the delete slot is repurposed with the remove label.
        val bar = ViewUtils.requireView(view, R.id.batch_action_bar)
        batchBar = bar
        batchCountView = bar.findViewById(R.id.batch_count)
        bar.findViewById<View>(R.id.batch_download).visibility = View.GONE
        bar.findViewById<View>(R.id.batch_category).visibility = View.GONE
        bar.findViewById<View>(R.id.batch_tankoubon).visibility = View.GONE
        bar.findViewById<View>(R.id.batch_clear_new).visibility = View.GONE
        bar.findViewById<View>(R.id.batch_select_all)
            .setOnClickListener { multiSelectHelper?.checkAll() }
        bar.findViewById<TextView>(R.id.batch_delete).apply {
            setText(R.string.batch_remove_history)
            setOnClickListener { onBatchRemoveClick() }
        }
        val multiSelect = ListMultiSelectHelper(
            model = viewModel.selection,
            recyclerView = { if (::mRecyclerView.isInitialized) mRecyclerView else null },
            keyAt = { position -> archiveAt(position)?.arcid },
            selectableKeys = { mRows.items.map { it.archive.arcid } },
            longClickListener = { this },
            onModeChanged = { active ->
                batchBar?.let { if (active) BatchBarAnimator.show(it) else BatchBarAnimator.hide(it) }
            },
            onCheckedChanged = { count ->
                batchCountView?.let {
                    it.text = it.resources.getQuantityString(R.plurals.batch_selected_count, count, count)
                }
            },
        )
        multiSelectHelper = multiSelect
        multiSelect.attach(mRecyclerView)
        multiSelect.attachDragSelect(mRecyclerView) { true }

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
        handlerDrawable.setColor(context.attrColor(R.attr.widgetColorThemeAccent))
        fastScroller.setHandlerDrawable(handlerDrawable)

        // The screen's state: rows diffed into the adapter, or empty / the error.
        // View-scoped: onCreateView3 re-runs on every detach/re-attach.
        collectFlow(viewLifecycleOwner, viewModel.state) { state ->
            mRows.submit(state.items)
            mStateList?.render(state)
            // A reload takes the checks of rows it no longer holds with it.
            if (state !is ListScreenState.Loading) {
                multiSelectHelper?.retainAll(state.items.map { it.archive.arcid })
            }
        }

        viewModel.loadHistory()

        return view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The scene's own instance: onCreate runs before every view creation.
        viewModel = ViewModelProvider(this)[HistoryViewModel::class.java]
        // Rating saved on the detail page: whole-lifetime collection, since
        // this list is covered by the detail page when the save lands.
        // The state change rebinds the row through the diff.
        collectFlowWhileCreated(this, AppEventBus.archiveRatingChangedEvent) { event ->
            viewModel.updateRating(event.arcid, event.rating)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setTitle(R.string.history)
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24)
        // View-scoped so a re-created view does not stack duplicate collectors.
        collectFlow(viewLifecycleOwner, viewModel.batchRemoveDone) { (ok, bad) ->
            val message = if (bad == 0) {
                resources.getQuantityString(R.plurals.batch_done_remove_history, ok, ok)
            } else {
                getString(R.string.batch_remove_history_partial, ok, bad)
            }
            showTip(message, LENGTH_SHORT)
        }
    }

    override fun onResume() {
        super.onResume()
        // Tank pseudo-rows bind their cover by TankCoverCacheStamp; rebind
        // them when it moved while this list was covered.
        val stamp = TankCoverCacheStamp.generation
        if (stamp != mTankCoverStampSeen) {
            mTankCoverStampSeen = stamp
            mRows.items.forEachIndexed { index, row ->
                if (isTankoubonId(row.archive.arcid)) mAdapter?.notifyItemChanged(index)
            }
        }
        // Refresh column size to pick up detail_size changes from settings
        if (::mLayoutManager.isInitialized) {
            val columnWidth = resources.getDimensionPixelOffset(AppearanceSettings.getDetailSizeResId())
            mLayoutManager.columnSize = columnWidth
            mRecyclerView.requestLayout()
        }
        // Reload history to pick up rating changes made in detail page. The
        // selection is by arcid, so it survives the reload (and a rotation).
        viewModel.loadHistory()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        if (mRows.items.isNotEmpty()) {
            // Adapter is being torn down with this view; a structural notify is
            // required so the framework drops cached ViewHolders that referenced
            // the history list. notifyDataSetChanged is acceptable here because
            // there is no concurrent dispatch path during view destruction.
            @Suppress("NotifyDataSetChanged")
            mAdapter?.notifyDataSetChanged()
        }
        // Dispatch nowhere until the next view; its adapter shows the same rows.
        mRows.adapter = null
        mRecyclerView.stopScroll()

        mStateList = null
        mAdapter = null
        multiSelectHelper?.detach()
        multiSelectHelper = null
        batchBar = null
        batchCountView = null
    }

    override fun hasInSceneBack(): Boolean = multiSelectHelper?.isActive == true

    override fun onBackPressed() {
        // Back leaves multi-select mode before leaving the scene.
        val multiSelect = multiSelectHelper
        if (multiSelect != null && multiSelect.isActive) {
            multiSelect.exit()
            return
        }
        super.onBackPressed()
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
                // The list is about to empty out: leave multi-select first.
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
        // In multi-select mode a click toggles the row instead of navigating
        // (mirrors gallery list).
        multiSelectHelper?.let { if (it.isActive) return it.toggleChecked(position) }
        val list = mRows.items.map { it.archive }
        if (position >= list.size) return false
        val archive = list[position]

        // A tank history row (composite-session bookkeeping) resumes the
        // whole-tank session directly — it has no per-archive detail page.
        if (isTankoubonId(archive.arcid)) {
            resumeTankSession(archive)
            return true
        }

        // A member of a tankoubon has no standalone identity either (spec
        // 2026-09-21 §5): resolve membership first; the tank session wins,
        // anything short of a confident answer opens the detail page.
        val ctx = ehContext ?: return false
        viewLifecycleOwner.lifecycleScope.launch {
            val intent = withContext(Dispatchers.IO) {
                HistoryTankRedirect.resumeLaunchOrNull(ctx, archive.arcid, archive.serverProfileId)
                    ?.let { GalleryOpenHelper.buildTankReadIntent(ctx, it) }
            }
            if (intent != null) startActivity(intent) else openArchiveDetail(list, position, archive, view)
        }
        return true
    }

    private fun openArchiveDetail(list: List<Archive>, position: Int, archive: Archive, view: View) {
        ReadingContextStore.publish(
            ReadingContext.LocalList(
                kind = ReadingContext.LocalList.Kind.HISTORY,
                forwardArchives = list
                    .subList(position, minOf(list.size, position + ReadingContextStore.LOCAL_WINDOW))
                    // Tank rows are never a standalone reading target — a
                    // "next archive" offer with a TANK_ id would break the
                    // per-archive reader it launches.
                    .filterNot { isTankoubonId(it.arcid) },
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
    }

    /**
     * Rebuild and launch the whole-tank composite session for a tank
     * history row: membership is re-fetched from the row's source server
     * (a stale snapshot could resume into removed members), the provider
     * restores the saved global progress. Failure surfaces a tip.
     */
    private fun resumeTankSession(archive: Archive) {
        val ctx = ehContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val intent = withContext(Dispatchers.IO) {
                    GalleryOpenHelper.buildTankReadIntent(
                        ctx, TankSessionRouter.resumeLaunch(ctx, archive.arcid, archive.serverProfileId)
                    )
                }
                startActivity(intent)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                showTip(friendlyError(ctx, e), LENGTH_SHORT)
            }
        }
    }

    override fun onItemLongClick(parent: EasyRecyclerView, view: View, position: Int, id: Long): Boolean =
        multiSelectHelper?.enterAndCheck(position) ?: false

    /**
     * Confirm-then-remove for the current selection. The selection is captured
     * before the dialog (it cannot change under a modal dialog) and
     * multi-select is left only on confirm — cancelling keeps the selection,
     * matching the gallery-list batch delete.
     */
    private fun onBatchRemoveClick() {
        val context = ehContext ?: return
        val multiSelect = multiSelectHelper ?: return
        val checked = multiSelect.checkedKeys
        val selected: List<Archive> = mRows.items.map { it.archive }.filter { it.arcid in checked }
        if (selected.isEmpty()) return
        AlertDialog.Builder(context)
            .setMessage(
                context.resources.getQuantityString(
                    R.plurals.batch_remove_history_confirm, selected.size, selected.size
                )
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.batch_remove_history) { _, _ ->
                // The removed rows leave the list; the mode ends with them.
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
            return archiveAt(position)?.arcid?.hashCode()?.toLong() ?: super.getItemId(position)
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
            val archive = archiveAt(position) ?: return
            holder.thumb.load(LRRCacheKeyFactory.getThumbKey(archive.arcid), ArchiveCoverStamps.bust(archive.thumbnailUrl, archive.arcid))
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
            return mRows.items.size
        }

        override fun onGetSwipeReactionType(holder: HistoryHolder, position: Int, x: Int, y: Int): Int {
            // A swipe removes a row; during multi-select taps and drags
            // belong to the selection.
            if (multiSelectHelper?.isActive == true) {
                return SwipeableItemConstants.REACTION_CAN_NOT_SWIPE_ANY
            }
            return SwipeableItemConstants.REACTION_CAN_SWIPE_LEFT
        }

        override fun onSwipeItemStarted(holder: HistoryHolder, position: Int) {}

        override fun onSetSwipeBackground(holder: HistoryHolder, position: Int, type: Int) {}

        override fun onSwipeItem(holder: HistoryHolder, position: Int, result: Int): SwipeResultAction {
            return when (result) {
                SwipeableItemConstants.RESULT_SWIPED_LEFT -> {
                    // Released past the threshold: the row is going.
                    Haptics.confirm(holder.itemView)
                    SwipeResultActionClear(position)
                }
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
            val info = mRows.items.getOrNull(mPosition)?.info ?: return

            viewModel.deleteHistoryItem(info)
        }
    }

    companion object {
        private const val REQUEST_CODE_GALLERY_DETAIL = 200
    }
}
