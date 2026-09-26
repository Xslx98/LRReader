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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.graphics.drawable.toDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.res.ResourcesCompat
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.hippo.android.resource.AttrResources
import com.lanraragi.framework.drawable.AddDeleteDrawable
import com.hippo.drawerlayout.DrawerLayout
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.easyrecyclerview.FastScroller
import com.hippo.easyrecyclerview.HandlerDrawable
import com.hippo.easyrecyclerview.MarginItemDecoration
import com.lanraragi.reader.Analytics
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.dao.DownloadInfo
import com.lanraragi.reader.gallery.TankMemberSeed
import com.lanraragi.reader.gallery.TankSeedStore
import com.lanraragi.reader.gallery.TankSessionSeed
import com.lanraragi.reader.ui.GalleryOpenHelper
import com.lanraragi.reader.download.DownloadManager
import com.lanraragi.reader.download.DownloadService
import com.lanraragi.reader.download.DownloadState
import com.lanraragi.reader.download.ProgressSnapshot
import com.lanraragi.reader.download.TankFillDispatcher
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.resolveSourceBaseUrl
import com.lanraragi.reader.settings.AppearanceSettings
import com.lanraragi.reader.ui.scene.ToolbarScene
import com.lanraragi.reader.ui.scene.download.part.DownloadAdapter
import com.lanraragi.reader.ui.scene.download.part.DownloadAdapter.Companion.DRAG_ENABLE
import com.lanraragi.reader.ui.scene.download.part.MyPageChangeListener
import com.lanraragi.reader.util.collectFlow
import com.lanraragi.reader.widget.MyEasyRecyclerView
import com.lanraragi.reader.widget.SearchBar
import com.lanraragi.framework.lib.yorozuya.AssertUtils
import com.lanraragi.framework.lib.yorozuya.ObjectUtils
import com.lanraragi.framework.lib.yorozuya.ViewUtils
import com.hippo.ripple.Ripple
import com.lanraragi.framework.scene.Announcer
import com.lanraragi.framework.util.DrawableManager
import com.lanraragi.framework.view.ViewTransition
import com.lanraragi.framework.widget.FabLayout
import com.lanraragi.framework.widget.ProgressView
import com.lanraragi.framework.unifile.UniFile
import com.lanraragi.framework.widget.SearchBarMover
import com.lanraragi.framework.widget.recyclerview.AutoStaggeredGridLayoutManager
import com.sxj.paginationlib.PaginationIndicator
import java.util.concurrent.CompletableFuture

class DownloadsScene : ToolbarScene(),
    EasyRecyclerView.OnItemClickListener,
    EasyRecyclerView.OnItemLongClickListener,
    FabLayout.OnClickFabListener, FabLayout.OnExpandListener,
    FastScroller.OnDragHandlerListener, SearchBar.Helper,
    SearchBarMover.Helper, SearchBar.OnStateChangeListener,
    DownloadAdapter.DownloadAdapterCallback {

    private var mActionFabDrawable: AddDeleteDrawable? = null

    /*---------------
     Whole life cycle
     ---------------*/
    private lateinit var viewModel: DownloadsViewModel

    /** Shortcut delegating to [DownloadsViewModel.currentLabel]. */
    var mLabel: String?
        get() = viewModel.currentLabel.value
        set(value) { viewModel.selectLabel(value) }

    /**
     * The label-filtered download list for rendering, sourced purely from the
     * Room Flow via [DownloadsViewModel.downloadList]. Transient progress
     * (speed / finished / total / remaining) is NOT carried on these items —
     * read it from [DownloadsViewModel.progressMap] or via
     * [DownloadManager.progressFor]. See ADR-001 Option D.
     *
     * Returns null only if the ViewModel has not yet been initialised; an
     * empty list means the active label currently has no downloads.
     */
    private val mList: List<DownloadInfo>?
        get() {
            if (!::viewModel.isInitialized) return null
            return viewModel.downloadList.value
        }

    private var mLastSnapshot: MutableList<DownloadInfo> = ArrayList()

    /**
     * Most recent progress map observed from [DownloadsViewModel.progressMap].
     * Used to compute a per-arcid delta and dispatch only the changed rows
     * via `notifyItemChanged(pos, PAYLOAD_PROGRESS)`.
     */
    private var mLastProgressMap: Map<String, ProgressSnapshot> = emptyMap()

    /*---------------
     List pagination
     ---------------*/
    private var myPageChangeListener: MyPageChangeListener? = null

    /*---------------
     View life cycle
     ---------------*/
    private lateinit var mRecyclerView: MyEasyRecyclerView
    private var mViewTransition: ViewTransition? = null
    private lateinit var mFabLayout: FabLayout
    private var mAdapter: RecyclerView.Adapter<*>? = null
    private var mOriginalAdapter: DownloadAdapter? = null
    private lateinit var mLayoutManager: AutoStaggeredGridLayoutManager

    // Helpers
    private var mDragDropHelper: DownloadDragDropHelper? = null
    private var mPaginationHelper: DownloadPaginationHelper? = null
    private var mSearchHelper: DownloadSearchHelper? = null
    private var mBatchOpsHelper: DownloadBatchOpsHelper? = null
    private var mGalleryOpenHelper: DownloadGalleryOpenHelper? = null
    private var mSelectionHelper: DownloadSelectionHelper? = null

    private lateinit var mProgressView: ProgressView

    private var mPaginationIndicator: PaginationIndicator? = null

    private var downloadLabelDraw: DownloadLabelDraw? = null

    var searchKey: String?
        get() = viewModel.searchKey.value
        set(value) { viewModel.setSearchKey(value) }

    private var mInitPosition = -1

    var searching: Boolean
        get() = viewModel.searching.value
        set(value) { viewModel.setSearching(value) }

    private val galleryActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> mGalleryOpenHelper?.updateReadProcess(result) }


    override fun getNavCheckedItem(): Int = R.id.nav_downloads

    private fun handleArguments(args: Bundle?) {
        if (args == null) return

        if (ACTION_CLEAR_DOWNLOAD_SERVICE == args.getString(KEY_ACTION)) {
            DownloadService.Companion.clear()
        }

        val arcid = args.getString(KEY_ARCID) ?: return
        // The DownloadManager's in-memory list may still be loading on a cold
        // start (it initializes asynchronously). Reading getDownloadInfo before
        // that completes returns null and the deep link — e.g. tapping the
        // "download complete" notification — silently no-ops. Await init first,
        // then do the lookup on the main thread (where the manager's mutators
        // are asserted to run). ::mRecyclerView.isInitialized already guards the
        // case where the view isn't built yet (stashes mInitPosition).
        lifecycleScope.launch {
            try {
                // awaitInitAsync asserts it is NOT on the main thread when init
                // is still pending (the case this whole block exists for), so
                // hop to IO for the wait; the lookup below resumes on Main.
                withContext(Dispatchers.IO) {
                    viewModel.downloadManager.awaitInitAsync()
                }
            } catch (e: Exception) {
                Log.e(TAG, "awaitInitAsync failed; deep-link lookup skipped", e)
                return@launch
            }
            val info = viewModel.downloadManager.getDownloadInfo(arcid) ?: return@launch
            viewModel.selectLabel(info.label)
            updateForLabel()
            updateView()

            val list = mList
            if (list != null) {
                val position = list.indexOf(info)
                if (position >= 0 && ::mRecyclerView.isInitialized) {
                    mPaginationHelper?.initPage(position, mList, mRecyclerView, mPaginationIndicator)
                } else {
                    mInitPosition = position
                }
            }
        }
    }

    override fun onNewArguments(args: Bundle) {
        handleArguments(args)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[DownloadsViewModel::class.java]

        val context = ehContext
        AssertUtils.assertNotNull(context)

        if (savedInstanceState == null) {
            onInit()
        } else {
            onRestore(savedInstanceState)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mActionFabDrawable = null
    }

    fun updateForLabel() {
        viewModel.updateForLabel()

        dispatchDiffUpdate(mList?.let { ArrayList(it) } ?: ArrayList())
        updateTitle()
        updatePaginationIndicator()
    }

    private fun updatePaginationIndicator() {
        mPaginationHelper?.updatePaginationIndicator(mPaginationIndicator, mList, myPageChangeListener)
    }

    @SuppressLint("StringFormatMatches")
    private fun updateTitle() {
        try {
            setTitle(
                getString(
                    R.string.scene_download_title_new,
                    mLabel ?: getString(R.string.default_download_label_name),
                    (mList?.size ?: 0).toString()
                )
            )
        } catch (e: Exception) {
            Analytics.recordException(e)
            setTitle(
                getString(
                    R.string.scene_download_title_new,
                    mLabel ?: getString(R.string.default_download_label_name)
                )
            )
        }
    }

    private fun onInit() {
        // Set up the default label immediately (the ViewModel initializes with
        // the recent label from settings); handleArguments asynchronously
        // refines to a deep-linked archive's label once the manager is ready.
        updateForLabel()
        handleArguments(arguments)
    }

    private fun onRestore(savedInstanceState: Bundle) {
        viewModel.selectLabel(savedInstanceState.getString(KEY_LABEL))
        updateForLabel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_LABEL, viewModel.currentLabel.value)
    }

    @Suppress("DEPRECATION")
    override fun onCreateView3(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.scene_download, container, false)

        val context = ehContext!!

        // Initialize search + batch-ops + gallery-open helpers
        mSearchHelper = DownloadSearchHelper(SearchHelperCallback())
        mBatchOpsHelper = DownloadBatchOpsHelper(BatchOpsHelperCallback())
        mGalleryOpenHelper = DownloadGalleryOpenHelper(GalleryOpenHelperCallback())
        mSelectionHelper = DownloadSelectionHelper(SelectionHelperCallback())

        mProgressView = ViewUtils.`$$`(view, R.id.download_progress_view) as ProgressView
        val content = ViewUtils.`$$`(view, R.id.content)
        mRecyclerView = ViewUtils.`$$`(content, R.id.recycler_view) as MyEasyRecyclerView
        val fastScroller = ViewUtils.`$$`(content, R.id.fast_scroller) as FastScroller
        mFabLayout = ViewUtils.`$$`(view, R.id.fab_layout) as FabLayout
        val tip = ViewUtils.`$$`(view, R.id.tip) as TextView
        // Initialize helpers
        mPaginationHelper = DownloadPaginationHelper(viewModel)
        mDragDropHelper = DownloadDragDropHelper()

        if (mPaginationIndicator != null) {
            mPaginationHelper?.needInitPage = true
        }
        mPaginationIndicator = ViewUtils.`$$`(view, R.id.indicator) as PaginationIndicator

        mPaginationIndicator?.setPerPageCountChoices(viewModel.perPageCountChoices, mPaginationHelper?.getPageSizePos(viewModel.pageSize.value) ?: 0)

        mViewTransition = ViewTransition(content, tip)

        val resources = context.resources

        val drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_download)
        drawable?.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        tip.setCompoundDrawables(null, drawable, null, null)
        // Initialize drag-drop via helper
        val originalAdapter = DownloadAdapter(this, this)
        mOriginalAdapter = originalAdapter
        originalAdapter.setHasStableIds(true)
        mAdapter = mDragDropHelper?.setup(context, originalAdapter) ?: originalAdapter
        mRecyclerView.adapter = mAdapter

        // Initialize pagination listener
        val paginationHelper = mPaginationHelper ?: return null
        myPageChangeListener = MyPageChangeListener(
            viewModel.indexPage.value, viewModel.pageSize.value,
            paginationHelper.needInitPage, paginationHelper.doNotScroll,
            mOriginalAdapter, mRecyclerView
        )

        // 设置分页监听器的回调
        myPageChangeListener?.pageChangeCallback = object : MyPageChangeListener.PageChangeCallback {
            override fun onPageChanged(newIndexPage: Int) {
                viewModel.setIndexPage(newIndexPage)
            }

            override fun onPageSizeChanged(newPageSize: Int) {
                viewModel.setPageSize(newPageSize)
            }
        }
        mLayoutManager = AutoStaggeredGridLayoutManager(0, StaggeredGridLayoutManager.VERTICAL)
        mLayoutManager.setColumnSize(resources.getDimensionPixelOffset(AppearanceSettings.getDetailSizeResId()))
        mLayoutManager.setStrategy(AutoStaggeredGridLayoutManager.STRATEGY_MIN_SIZE)

        // Configure drag-related RecyclerView properties
        mDragDropHelper?.configureRecyclerView(mRecyclerView)

        mRecyclerView.setItemViewCacheSize(100)
        mRecyclerView.layoutManager = mLayoutManager
        mRecyclerView.selector = Ripple.generateRippleDrawable(
            context,
            !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme),
            Color.TRANSPARENT.toDrawable()
        )
        mRecyclerView.setDrawSelectorOnTop(true)
        mRecyclerView.clipToPadding = false
        mRecyclerView.setOnItemClickListener(this)
        mRecyclerView.setOnItemLongClickListener(this)
        mRecyclerView.setChoiceMode(EasyRecyclerView.CHOICE_MODE_MULTIPLE_CUSTOM)
        mRecyclerView.setCustomCheckedListener(mSelectionHelper?.choiceListener)
        mSelectionHelper?.attachDragSelect(mRecyclerView)
        val interval = resources.getDimensionPixelOffset(R.dimen.gallery_list_interval)
        val paddingH = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_h)
        val paddingV = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_v)
        val decoration = MarginItemDecoration(interval, paddingH, paddingV, paddingH, paddingV)
        mRecyclerView.addItemDecoration(decoration)
        decoration.applyPaddings(mRecyclerView)

        // Attach drag-drop manager to RecyclerView
        mDragDropHelper?.attachToRecyclerView(mRecyclerView)

        if (mInitPosition >= 0 && viewModel.indexPage.value != 1) {
            mPaginationHelper?.initPage(mInitPosition, mList, mRecyclerView, mPaginationIndicator)
            mRecyclerView.scrollToPosition(listIndexInPage(mInitPosition))
            mInitPosition = -1
        }

        fastScroller.attachToRecyclerView(mRecyclerView)
        val handlerDrawable = HandlerDrawable()
        handlerDrawable.setColor(AttrResources.getAttrColor(context, R.attr.widgetColorThemeAccent))
        fastScroller.setHandlerDrawable(handlerDrawable)
        fastScroller.setOnDragHandlerListener(this)

        mFabLayout.setExpanded(false, true)
        mFabLayout.setHidePrimaryFab(false)
        mFabLayout.setAutoCancel(false)
        mFabLayout.setOnClickFabListener(this)
        mFabLayout.setOnExpandListener(this)
        mActionFabDrawable = AddDeleteDrawable(context, resources.getColor(R.color.primary_drawable_dark, null))
        mFabLayout.primaryFab.setImageDrawable(mActionFabDrawable)
        val fab = mFabLayout.getSecondaryFabAt(6)
        if (DRAG_ENABLE) {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_x24, context.theme))
        } else {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_off_x24, context.theme))
        }
        addAboveSnackView(mFabLayout)

        updateView()

        updatePaginationIndicator()
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateTitle()
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24)

        // Subscribe to the structural list StateFlow (Room Flow, label-
        // filtered). Transient progress flows separately via progressMap
        // below. DiffUtil compares structural fields only.
        collectFlow(viewLifecycleOwner, viewModel.downloadList) { list ->
            if (mAdapter == null || searching) return@collectFlow
            dispatchDiffUpdate(ArrayList(list))
            updateTitle()
            updatePaginationIndicator()
            updateView()
        }

        // Subscribe to the live progress map. For each arcid whose snapshot
        // changed since the last emission, fire a targeted
        // notifyItemChanged(pos, PAYLOAD_PROGRESS) so only the progress views
        // repaint — no DiffUtil, no full rebind. Fixes the progress-freeze
        // bug introduced by the earlier mutating-combine approach.
        collectFlow(viewLifecycleOwner, viewModel.progressMap) { progressMap ->
            if (mAdapter == null || searching) return@collectFlow
            dispatchProgressChanges(progressMap)
        }

        // Observe filter loading state
        collectFlow(viewLifecycleOwner, viewModel.filterLoading) { loading ->
            if (loading) {
                mProgressView.visibility = View.VISIBLE
                mRecyclerView.visibility = View.GONE
            } else {
                mProgressView.visibility = View.GONE
                mRecyclerView.visibility = View.VISIBLE
            }
        }

        // Observe filter/sort/search completion
        collectFlow(viewLifecycleOwner, viewModel.filterSearchDone) {
            if (!isAdded) return@collectFlow
            updateAdapter()
            // The downloadList collector skips updates while searching, so the
            // count in the title and the empty view must be refreshed here.
            updateTitle()
            updatePaginationIndicator()
            updateView()
        }

        // ── Observe DownloadInfoListener events from ViewModel (sealed dispatch) ──

        collectFlow(viewLifecycleOwner, viewModel.downloadEvent) { event ->
            when (event) {
                // Structural events are covered by the downloadList Flow
                // above; transient progress by the progressMap Flow. These
                // listener callbacks exist only so downstream consumers
                // (e.g. the rating sync path inside GalleryDetailViewModel)
                // can react — the Scene itself just refreshes its empty-view.
                is DownloadUiEvent.ItemAdded,
                is DownloadUiEvent.ItemRemoved,
                is DownloadUiEvent.DiffUpdate,
                is DownloadUiEvent.Reloaded,
                is DownloadUiEvent.ItemUpdated -> {
                    updateView()
                }
                is DownloadUiEvent.Replaced -> {
                    updateForLabel()
                    updateView()
                }
                is DownloadUiEvent.LabelRenamed -> {
                    viewModel.handleLabelRenamed(event.from, event.to)
                    updateForLabel()
                    updateView()
                }
                is DownloadUiEvent.LabelDeleted -> {
                    viewModel.resetToDefaultLabel()
                    updateForLabel()
                    updateView()
                }
                is DownloadUiEvent.LabelsChanged -> {
                    downloadLabelDraw?.updateDownloadLabels()
                }
            }
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
    }

    override fun onDestroyView() {
        super.onDestroyView()

        mDragDropHelper?.cleanup()
        mDragDropHelper = null
        mPaginationHelper = null
        mSearchHelper = null
        mBatchOpsHelper = null
        mGalleryOpenHelper = null
        mSelectionHelper = null

        if (::mRecyclerView.isInitialized) {
            mRecyclerView.stopScroll()
        }
        if (::mFabLayout.isInitialized) {
            removeAboveSnackView(mFabLayout)
        }

        mViewTransition = null
        mAdapter = null
        mOriginalAdapter = null
    }

    override fun onNavigationClick(view: View) {
        onBackPressed()
    }

    override fun getMenuResId(): Int = R.menu.scene_download

    @SuppressLint("NonConstantResourceId")
    override fun onMenuItemClick(item: MenuItem): Boolean {
        val activity = activity2
        if (activity == null || !::mRecyclerView.isInitialized || mRecyclerView.isInCustomChoice) return false
        return handleMenuAction(item.itemId, activity)
    }

    private fun handleMenuAction(itemId: Int, activity: android.app.Activity): Boolean = when (itemId) {
        R.id.action_start_all -> { mBatchOpsHelper?.startAll(activity); true }
        R.id.action_stop_all -> { mBatchOpsHelper?.stopAll(); true }
        R.id.action_reset_reading_progress -> { mBatchOpsHelper?.resetReadingProgress(searching); true }
        R.id.search_download_gallery -> { ehContext?.let { mSearchHelper?.gotoSearch(it) } != null }
        R.id.all, R.id.sort_by_default, R.id.download_done, R.id.not_started,
        R.id.waiting, R.id.downloading, R.id.failed,
        R.id.sort_by_gallery_id_asc, R.id.sort_by_gallery_id_desc,
        R.id.sort_by_create_time_asc, R.id.sort_by_create_time_desc,
        R.id.sort_by_rating_asc, R.id.sort_by_rating_desc,
        R.id.sort_by_name_asc, R.id.sort_by_name_desc,
        R.id.sort_by_file_size_asc, R.id.sort_by_file_size_desc -> {
            viewModel.gotoFilterAndSort(itemId); true
        }
        else -> false
    }

    fun updateView() {
        val viewTransition = mViewTransition ?: return
        if (mList.isNullOrEmpty()) {
            viewTransition.showView(1)
        } else {
            viewTransition.showView(0)
        }
    }

    /** Applies DiffUtil against [mLastSnapshot] and dispatches updates to [mAdapter]. */
    private fun dispatchDiffUpdate(newList: MutableList<DownloadInfo>) {
        val result = DiffUtil.calculateDiff(DownloadInfoDiffCallback(mLastSnapshot, newList))
        mLastSnapshot = newList
        mAdapter?.let { result.dispatchUpdatesTo(it) }
    }

    /**
     * Dispatch targeted progress updates for any arcid whose [ProgressSnapshot]
     * changed since the last emission. Uses a payload so [DownloadAdapter]
     * can rebind only the progress / speed / percent views — no full item
     * rebind, no image re-fetch.
     */
    private fun dispatchProgressChanges(newMap: Map<String, ProgressSnapshot>) {
        val oldMap = mLastProgressMap
        mLastProgressMap = newMap
        val list = mList ?: return
        val adapter = mAdapter ?: return
        // Collect arcids whose snapshot meaningfully differs (or was
        // added/removed). ProgressSnapshot is a data class so `!=` compares
        // by value — same arcid, same fields → skip.
        val affected = HashSet<String>()
        for ((arcid, snap) in newMap) {
            if (oldMap[arcid] != snap) affected.add(arcid)
        }
        for (arcid in oldMap.keys) {
            if (arcid !in newMap) affected.add(arcid)
        }
        if (affected.isEmpty()) return
        // A member's tick must repaint the CARD that folds it (the member
        // row itself is not on the display list).
        for (arcid in affected.toList()) {
            viewModel.tankCardIdFor(arcid)?.let { affected.add(it) }
        }
        for ((indexInList, info) in list.withIndex()) {
            val id = info.arcid ?: continue
            if (id !in affected) continue
            // Null = not on the page currently shown; notifying the modulo
            // position would rebind the wrong visible row.
            val inPagePos = viewModel.adapterPositionForListIndex(indexInList) ?: continue
            adapter.notifyItemChanged(inPagePos, PAYLOAD_PROGRESS)
        }
    }

    override fun onCreateDrawerView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val draw = downloadLabelDraw ?: DownloadLabelDraw(inflater, container, LabelDrawCallback()).also { downloadLabelDraw = it }
        return draw.createView()
    }

    override fun onBackPressed() {
        if (::mRecyclerView.isInitialized && mRecyclerView.isInCustomChoice) {
            mRecyclerView.outOfCustomChoiceMode()
        } else {
            super.onBackPressed()
        }
    }

    override fun onStartDragHandler() {
        // Lock right drawer
        setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT)
    }

    override fun onEndDragHandler() {
        // Restore right drawer
        if (::mRecyclerView.isInitialized && !mRecyclerView.isInCustomChoice) {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT)
        }
    }

    override fun onItemClick(parent: EasyRecyclerView, view: View, position: Int, id: Long): Boolean {
        if (activity2 == null || !::mRecyclerView.isInitialized) return false
        return mGalleryOpenHelper?.onItemClick(position) ?: false
    }

    override fun onItemLongClick(parent: EasyRecyclerView, view: View, position: Int, id: Long): Boolean =
        mSelectionHelper?.onItemLongClick(position) ?: false

    @SuppressLint("RtlHardcoded")
    override fun onExpand(expanded: Boolean) {
        mSelectionHelper?.onExpand(expanded)
    }

    override fun onClickPrimaryFab(view: FabLayout, fab: FloatingActionButton?) {
        mSelectionHelper?.onClickPrimaryFab(view, fab)
    }

    override fun onClickSecondaryFab(view: FabLayout, fab: FloatingActionButton, position: Int) {
        mBatchOpsHelper?.onClickSecondaryFab(fab, position)
    }

    // DownloadAdapterCallback interface implementation
    override val indexPage: Int
        get() = viewModel.indexPage.value

    override val pageSize: Int
        get() = viewModel.pageSize.value

    override val paginationSize: Int
        get() = viewModel.paginationSize

    override val isCanPagination: Boolean
        get() = viewModel.canPagination

    override fun positionInList(position: Int): Int = viewModel.positionInList(position)

    override fun listIndexInPage(position: Int): Int = viewModel.listIndexInPage(position)

    override val list: List<DownloadInfo>?
        get() = mList

    override val downloadManager: DownloadManager?
        get() = viewModel.downloadManager

    override val recyclerView: EasyRecyclerView?
        get() = if (::mRecyclerView.isInitialized) mRecyclerView else null

    override fun downloadDirFutureFor(info: DownloadInfo): CompletableFuture<UniFile?> =
        viewModel.downloadDirFutureFor(info)

    override fun tankProgressSnapshotFor(tankId: String): ProgressSnapshot? =
        viewModel.tankProgressSnapshot(tankId)

    override fun tankMemberCountFor(tankId: String): Int = viewModel.tankMembersOf(tankId).size

    // ── Tank download cards (Track 2) ─────────────────────────

    /**
     * Open a tank card: rebuild the whole-tank composite session from the
     * PERSISTED group snapshot (works fully offline for downloaded
     * members; pagecounts ride the member rows' archive_json).
     */
    internal fun openTankCard(info: DownloadInfo) {
        val activity = activity2 ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val members = withContext(Dispatchers.IO) {
                    ServiceRegistry.dataModule.downloadDbRepository
                        .getTankMemberArchives(info.arcid)
                }
                if (members.isEmpty()) return@launch
                val seed = TankSessionSeed(
                    tankId = info.arcid,
                    tankName = info.title.orEmpty(),
                    profileId = info.serverProfileId,
                    members = members.map { TankMemberSeed(it.arcid, it.title, it.pagecount) },
                )
                TankSeedStore.publish(seed)
                galleryActivityLauncher.launch(GalleryOpenHelper.buildTankReadIntent(activity, seed))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to open tank card ${info.arcid}", e)
            }
        }
    }

    /**
     * Card start control (spec 2026-09-21 §4): fill the tank through the
     * shared [TankFillDispatcher] — members already on disk are skipped,
     * partial / failed ones restart, and group ids WITHOUT a download row
     * (the INCOMPLETE case) get their metadata from the source server
     * before enqueueing. Unreachable metadata is reported, never silently
     * dropped; the group row is re-tagged with the full membership.
     */
    internal fun onTankCardStart(card: DownloadInfo) {
        val context = ehContext ?: return
        val tankId = card.arcid
        val profileId = card.serverProfileId
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = ServiceRegistry.dataModule
                val (ids, resolved) = withContext(Dispatchers.IO) {
                    val ids = data.downloadDbRepository.getTankGroupMemberIds(tankId)
                    val present = data.downloadDbRepository.getTankMemberArchives(tankId)
                    val url = runCatching {
                        resolveSourceBaseUrl(profileId, data.profileLookupCache)
                    }.getOrNull()
                    val client = ServiceRegistry.networkModule.okHttpClient
                    ids to TankFillDispatcher.resolveMembers(ids, present) { id ->
                        if (url == null) {
                            null
                        } else {
                            runCatching {
                                LRRArchiveApi.getArchiveMetadata(client, url, id)
                                    .toArchive(sourceProfileId = profileId, sourceBaseUrl = url)
                            }.getOrNull()
                        }
                    }
                }
                val ctx = ehContext ?: return@launch
                val dm = viewModel.downloadManager
                val plan = TankFillDispatcher.plan(resolved.members) { dm.getDownloadState(it) }
                TankFillDispatcher.dispatch(ctx, dm, plan, tankId, card.title.orEmpty(), profileId, ids)
                Toast.makeText(ctx, TankFillDispatcher.feedback(resources, plan), Toast.LENGTH_SHORT).show()
                if (resolved.unresolved.isNotEmpty()) {
                    val n = resolved.unresolved.size
                    Toast.makeText(
                        ctx, resources.getQuantityString(R.plurals.tank_fill_unreachable, n, n), Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to start tank card", e)
                Toast.makeText(context, R.string.error_unknown, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Card stop control: halt every member still queued or downloading. */
    internal fun onTankCardStop(card: DownloadInfo) {
        val active = viewModel.tankMembersOf(card.arcid)
            .filter { it.state == DownloadState.WAIT || it.state == DownloadState.DOWNLOAD }
            .map { it.arcid }
        if (active.isNotEmpty()) viewModel.stopRangeDownloads(active)
    }

    override fun onClickTitle() {
        mSearchHelper?.enterSearchMode(true)
    }

    override fun onClickLeftIcon() {
        // No-op
    }

    override fun onClickRightIcon() {
        mSearchHelper?.searchBar?.applySearch(true)
    }

    override fun onSearchEditTextClick() {
        // No-op
    }

    override fun onApplySearch(query: String) {
        mSearchHelper?.onApplySearch(query)
    }

    /** Delegates to [DownloadSearchHelper.startSearching]. Called from [DownloadLabelDraw]. */
    internal fun startSearching() {
        mSearchHelper?.startSearching()
    }

    private fun updateAdapter() {
        // 检查 Fragment 是否已附加，如果未附加则延迟创建适配器
        if (!isAdded) {
            return
        }
        val newAdapter = DownloadAdapter(this, this)
        mOriginalAdapter = newAdapter
        newAdapter.setHasStableIds(true)
        // 避免重复创建包装适配器，直接使用原始适配器
        mAdapter = mOriginalAdapter
        if (::mRecyclerView.isInitialized) {
            mRecyclerView.adapter = mAdapter
        }
    }

    override fun onSearchEditTextBackPressed() {
        mSearchHelper?.onSearchEditTextBackPressed()
    }

    override fun onStateChange(searchBar: SearchBar, newState: Int, oldState: Int, animation: Boolean) {
        // No-op
    }

    override fun isValidView(recyclerView: RecyclerView): Boolean = false

    override fun getValidRecyclerView(): RecyclerView? = if (::mRecyclerView.isInitialized) mRecyclerView else null

    override fun forceShowSearchBar(): Boolean = false

    fun runOnUiThread(runnable: Runnable) {
        activity2?.runOnUiThread(runnable)
    }

    // ── Callback implementations for helpers ──

    private inner class GalleryOpenHelperCallback : DownloadGalleryOpenHelper.Callback {
        override val ehContext: Context? get() = this@DownloadsScene.ehContext
        override val activity2: android.app.Activity? get() = this@DownloadsScene.activity2
        override val viewModel: DownloadsViewModel get() = this@DownloadsScene.viewModel
        override val mList: List<DownloadInfo>? get() = this@DownloadsScene.mList
        override val mRecyclerView: EasyRecyclerView? get() = this@DownloadsScene.recyclerView
        override val mAdapter: RecyclerView.Adapter<*>? get() = this@DownloadsScene.mAdapter
        override val viewLifecycleOwner get() = this@DownloadsScene.viewLifecycleOwner
        override fun positionInList(position: Int): Int = this@DownloadsScene.positionInList(position)
        override fun listIndexInPage(position: Int): Int = this@DownloadsScene.listIndexInPage(position)
        override fun launchGallery(intent: Intent) = galleryActivityLauncher.launch(intent)
        override fun openTankCard(info: DownloadInfo) = this@DownloadsScene.openTankCard(info)
    }

    private inner class SelectionHelperCallback : DownloadSelectionHelper.Callback {
        override val mRecyclerView: EasyRecyclerView? get() = this@DownloadsScene.recyclerView
        override val mFabLayout: FabLayout? get() = if (this@DownloadsScene::mFabLayout.isInitialized) this@DownloadsScene.mFabLayout else null
        override val actionFabDrawable: AddDeleteDrawable? get() = mActionFabDrawable
        override val longClickListener: EasyRecyclerView.OnItemLongClickListener get() = this@DownloadsScene
        override fun setDrawerLockMode(lockMode: Int, gravity: Int) =
            this@DownloadsScene.setDrawerLockMode(lockMode, gravity)
    }

    private inner class LabelDrawCallback : DownloadLabelDraw.Callback {
        override val ehContext: Context? get() = this@DownloadsScene.ehContext
        override val currentLabel: String? get() = this@DownloadsScene.mLabel
        override val searching: Boolean get() = this@DownloadsScene.searching
        override val searchKey: String? get() = this@DownloadsScene.searchKey
        override val downloadManager: DownloadManager get() = this@DownloadsScene.viewModel.downloadManager
        override fun getString(resId: Int): String = this@DownloadsScene.getString(resId)
        override fun startScene(announcer: Announcer) = this@DownloadsScene.startScene(announcer)
        override fun selectLabel(label: String?) { this@DownloadsScene.mLabel = label }
        override fun updateForLabel() = this@DownloadsScene.updateForLabel()
        override fun startSearching() = this@DownloadsScene.startSearching()
        override fun updateView() = this@DownloadsScene.updateView()
        override fun closeDrawer(gravity: Int) = this@DownloadsScene.closeDrawer(gravity)
    }

    private inner class SearchHelperCallback : DownloadSearchHelper.Callback {
        override val ehContext: Context? get() = this@DownloadsScene.ehContext
        override val viewModel: DownloadsViewModel get() = this@DownloadsScene.viewModel
        override fun updateForLabel() = this@DownloadsScene.updateForLabel()
        override fun updateView() = this@DownloadsScene.updateView()
        override val searchBarHelper: SearchBar.Helper get() = this@DownloadsScene
        override val searchBarMoverHelper: SearchBarMover.Helper get() = this@DownloadsScene
    }

    private inner class BatchOpsHelperCallback : DownloadBatchOpsHelper.Callback {
        override val ehContext: Context? get() = this@DownloadsScene.ehContext
        override val activity2: android.app.Activity? get() = this@DownloadsScene.activity2
        override val viewModel: DownloadsViewModel get() = this@DownloadsScene.viewModel
        override val mList: List<DownloadInfo>? get() = this@DownloadsScene.mList
        override val mRecyclerView: EasyRecyclerView? get() = this@DownloadsScene.recyclerView
        override val mFabLayout: FabLayout? get() = if (this@DownloadsScene::mFabLayout.isInitialized) this@DownloadsScene.mFabLayout else null
        override fun positionInList(position: Int): Int = this@DownloadsScene.positionInList(position)
        override fun onClickPrimaryFab(view: FabLayout, fab: FloatingActionButton?) =
            this@DownloadsScene.onClickPrimaryFab(view, fab)
        override fun launchGallery(intent: Intent) = galleryActivityLauncher.launch(intent)
        override fun getResources(): android.content.res.Resources = this@DownloadsScene.getResources()
    }

    companion object {
        private val TAG = DownloadsScene::class.java.simpleName

        const val KEY_ARCID = "arcid"
        const val KEY_ACTION = "action"
        private const val KEY_LABEL = "label"

        const val ACTION_CLEAR_DOWNLOAD_SERVICE = "clear_download_service"

        const val LOCAL_GALLERY_INFO_CHANGE = 909

        /**
         * Payload token for [RecyclerView.Adapter.notifyItemChanged] that
         * signals "only the transient progress views changed — keep the rest
         * of the row intact." [DownloadAdapter.onBindViewHolder] checks for
         * this and skips the full rebind (image load, tag render, etc.).
         */
        const val PAYLOAD_PROGRESS = "progress"
    }
}

/**
 * DiffUtil callback for DownloadInfo lists.
 *
 * Identity: [DownloadInfo.arcid]. Content comparison covers only persistent
 * / structural fields — transient progress (speed / downloaded / total /
 * finished / remaining) is intentionally excluded because it flows through
 * [DownloadsScene.dispatchProgressChanges] with the `PAYLOAD_PROGRESS`
 * payload, not through DiffUtil. Including those fields here would defeat
 * that split and (prior to the W35-3b post-mortem fix) led to frozen
 * progress due to shared-reference mutation. See ADR-001 Option D.
 */
internal class DownloadInfoDiffCallback(
    private val oldList: List<DownloadInfo>,
    private val newList: List<DownloadInfo>
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int = oldList.size

    override fun getNewListSize(): Int = newList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition].arcid == newList[newItemPosition].arcid
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val oldItem = oldList[oldItemPosition]
        val newItem = newList[newItemPosition]
        return oldItem.state == newItem.state &&
            oldItem.legacy == newItem.legacy &&
            ObjectUtils.equal(oldItem.label, newItem.label) &&
            ObjectUtils.equal(oldItem.title, newItem.title) &&
            ObjectUtils.equal(oldItem.thumb, newItem.thumb) &&
            // Tank cards: a membership reconcile can change ONLY the missing
            // count (Done → Incomplete) — that must re-bind the status line.
            oldItem.tankMissingCount == newItem.tankMissingCount
    }
}
