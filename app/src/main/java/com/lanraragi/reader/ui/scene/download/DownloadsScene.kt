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
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.lanraragi.framework.drawable.AddDeleteDrawable
import com.lanraragi.reader.widget.drawer.DrawerLayout
import com.lanraragi.reader.widget.list.EasyRecyclerView
import com.lanraragi.reader.widget.list.FastScroller
import com.lanraragi.reader.Analytics
import com.lanraragi.reader.R
import com.lanraragi.reader.dao.DownloadInfo
import com.lanraragi.reader.download.DownloadManager
import com.lanraragi.reader.download.ProgressSnapshot
import com.lanraragi.reader.settings.AppearanceSettings
import com.lanraragi.reader.ui.scene.ToolbarScene
import com.lanraragi.reader.ui.scene.download.part.DownloadAdapter
import com.lanraragi.reader.ui.scene.download.part.MyPageChangeListener
import com.lanraragi.reader.widget.MyEasyRecyclerView
import com.lanraragi.reader.widget.SearchBar
import com.lanraragi.reader.util.AssertUtils
import com.lanraragi.reader.util.ViewUtils
import com.lanraragi.framework.scene.Announcer
import androidx.core.content.ContextCompat
import com.lanraragi.reader.widget.ViewTransition
import com.lanraragi.framework.widget.FabLayout
import com.lanraragi.framework.widget.ProgressView
import com.lanraragi.reader.storage.StorageFile
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

    internal val listDispatcher by lazy {
        DownloadListDispatcher(viewModel, adapter = { mAdapter }, list = { mList })
    }

    /*---------------
     List pagination
     ---------------*/
    private var myPageChangeListener: MyPageChangeListener? = null

    /*---------------
     View life cycle
     ---------------*/
    private lateinit var mRecyclerView: MyEasyRecyclerView
    private var mViewTransition: ViewTransition? = null
    private var mTip: TextView? = null

    /** Last [com.lanraragi.reader.download.DownloadManager.loadError] (audit C34 / STAB-13). */
    internal var loadError: Throwable? = null
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

    private val deepLink by lazy { DownloadDeepLink(this, viewModel) }

    var searching: Boolean
        get() = viewModel.searching.value
        set(value) { viewModel.setSearching(value) }

    private val galleryActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> mGalleryOpenHelper?.updateReadProcess(result) }

    private val tankCards by lazy {
        DownloadTankCards(this, viewModel, launchGallery = { galleryActivityLauncher.launch(it) })
    }


    override fun getNavCheckedItem(): Int = R.id.nav_downloads

    private fun handleArguments(args: Bundle?) {
        deepLink.handle(args)
    }

    /** True once onCreateView3 has created the list view. */
    internal val isListViewCreated: Boolean get() = ::mRecyclerView.isInitialized

    /** Turns the pagination to the page of list [position], and scrolls to it when [scroll]. */
    internal fun showListPosition(position: Int, scroll: Boolean) {
        mPaginationHelper?.initPage(position, mList, mRecyclerView, mPaginationIndicator)
        if (scroll) mRecyclerView.scrollToPosition(listIndexInPage(position))
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
        // The ViewModel is activity-scoped: without this, reopening Downloads
        // would show a list silently filtered by the last search.
        if (::viewModel.isInitialized && activity?.isChangingConfigurations != true) viewModel.clearSearch()
    }

    fun updateForLabel() {
        viewModel.updateForLabel()

        listDispatcher.dispatchDiffUpdate(mList?.let { ArrayList(it) } ?: ArrayList())
        updateTitle()
        updatePaginationIndicator()
    }

    internal fun updatePaginationIndicator() {
        mPaginationHelper?.updatePaginationIndicator(mPaginationIndicator, mList, myPageChangeListener)
    }

    @SuppressLint("StringFormatMatches")
    internal fun updateTitle() {
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

        mProgressView = ViewUtils.requireView(view, R.id.download_progress_view) as ProgressView
        val content = ViewUtils.requireView(view, R.id.content)
        mRecyclerView = ViewUtils.requireView(content, R.id.recycler_view) as MyEasyRecyclerView
        val fastScroller = ViewUtils.requireView(content, R.id.fast_scroller) as FastScroller
        mFabLayout = ViewUtils.requireView(view, R.id.fab_layout) as FabLayout
        val tip = ViewUtils.requireView(view, R.id.tip) as TextView
        // Initialize helpers
        mPaginationHelper = DownloadPaginationHelper(viewModel)
        mDragDropHelper = DownloadDragDropHelper()

        if (mPaginationIndicator != null) {
            mPaginationHelper?.needInitPage = true
        }
        mPaginationIndicator = ViewUtils.requireView(view, R.id.indicator) as PaginationIndicator

        mPaginationIndicator?.setPerPageCountChoices(viewModel.perPageCountChoices, mPaginationHelper?.getPageSizePos(viewModel.pageSize.value) ?: 0)

        mViewTransition = ViewTransition(content, tip)
        mTip = tip

        val resources = context.resources

        val drawable = ContextCompat.getDrawable(context, R.drawable.big_download)
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
        mLayoutManager = DownloadListViews.configureList(
            this, context, mRecyclerView, mSelectionHelper, mDragDropHelper
        )

        deepLink.restorePendingPosition()

        DownloadListViews.configureFastScroller(context, fastScroller, mRecyclerView, this)

        mActionFabDrawable = DownloadListViews.configureFab(this, context, mFabLayout)
        addAboveSnackView(mFabLayout)

        updateView()

        updatePaginationIndicator()
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateTitle()
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24)

        DownloadsEvents(this, viewModel).install(viewLifecycleOwner)
    }

    override fun onResume() {
        super.onResume()
        // Refresh column size to pick up detail_size changes from settings
        if (::mLayoutManager.isInitialized) {
            val columnWidth = resources.getDimensionPixelOffset(AppearanceSettings.getDetailSizeResId())
            mLayoutManager.columnSize = columnWidth
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
        mTip = null
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

    /** False until onCreateView3 attached the list adapter, and again after onDestroyView. */
    internal val hasAdapter: Boolean get() = mAdapter != null

    internal fun showFilterLoading(loading: Boolean) {
        if (loading) {
            mProgressView.visibility = View.VISIBLE
            mRecyclerView.visibility = View.GONE
        } else {
            mProgressView.visibility = View.GONE
            mRecyclerView.visibility = View.VISIBLE
        }
    }

    internal fun updateDownloadLabels() {
        downloadLabelDraw?.updateDownloadLabels()
    }

    fun updateView() {
        val viewTransition = mViewTransition ?: return
        val tip = mTip
        if (loadError != null && tip != null) {
            tip.setText(R.string.downloads_load_failed)
            tip.setOnClickListener { viewModel.downloadManager.retryLoading() }
            viewTransition.showView(1)
            return
        }
        tip?.setText(R.string.no_download_info)
        tip?.setOnClickListener(null)
        tip?.isClickable = false
        if (mList.isNullOrEmpty()) {
            viewTransition.showView(1)
        } else {
            viewTransition.showView(0)
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

    override fun hasInSceneBack(): Boolean =
        ::mRecyclerView.isInitialized && mRecyclerView.isInCustomChoice

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

    override fun downloadDirFutureFor(info: DownloadInfo): CompletableFuture<StorageFile?> =
        viewModel.downloadDirFutureFor(info)

    override fun tankProgressSnapshotFor(tankId: String): ProgressSnapshot? =
        viewModel.tankProgressSnapshot(tankId)

    override fun tankMemberCountFor(tankId: String): Int = viewModel.tankMembersOf(tankId).size

    // ── Tank download cards (Track 2) ─────────────────────────

    internal fun openTankCard(info: DownloadInfo) = tankCards.open(info)

    internal fun onTankCardStart(card: DownloadInfo) = tankCards.start(card)

    internal fun onTankCardStop(card: DownloadInfo) = tankCards.stop(card)

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

    internal fun updateAdapter() {
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
        override val viewLifecycleOwner get() = this@DownloadsScene.viewLifecycleOwner
        override fun positionInList(position: Int): Int = this@DownloadsScene.positionInList(position)
        override fun onClickPrimaryFab(view: FabLayout, fab: FloatingActionButton?) =
            this@DownloadsScene.onClickPrimaryFab(view, fab)
        override fun launchGallery(intent: Intent) = galleryActivityLauncher.launch(intent)
        override fun getResources(): android.content.res.Resources = this@DownloadsScene.getResources()
    }

    companion object {
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
