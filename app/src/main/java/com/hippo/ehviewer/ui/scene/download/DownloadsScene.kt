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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.hippo.android.resource.AttrResources
import com.hippo.drawable.AddDeleteDrawable
import com.hippo.drawerlayout.DrawerLayout
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.easyrecyclerview.FastScroller
import com.hippo.easyrecyclerview.HandlerDrawable
import com.hippo.easyrecyclerview.MarginItemDecoration
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.R
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.DownloadService
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.ui.scene.ToolbarScene
import com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter
import com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter.Companion.DRAG_ENABLE
import com.hippo.ehviewer.ui.scene.download.part.MyPageChangeListener
import com.hippo.ehviewer.util.collectFlow
import com.hippo.ehviewer.widget.MyEasyRecyclerView
import com.hippo.ehviewer.widget.SearchBar
import com.hippo.lib.yorozuya.AssertUtils
import com.hippo.lib.yorozuya.ObjectUtils
import com.hippo.lib.yorozuya.ViewUtils
import com.hippo.ripple.Ripple
import com.hippo.scene.Announcer
import com.hippo.util.DrawableManager
import com.hippo.view.ViewTransition
import com.hippo.widget.FabLayout
import com.hippo.widget.ProgressView
import com.hippo.widget.SearchBarMover
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager
import com.sxj.paginationlib.PaginationIndicator

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

    /** Shortcut delegating to [DownloadsViewModel.downloadList]. */
    private var mList: List<DownloadInfo>?
        get() = viewModel.downloadList.value
        set(value) { if (value != null) viewModel.setDownloadList(value) }

    private var mLastSnapshot: MutableList<DownloadInfo> = ArrayList()

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
    private var mGuideHelper: DownloadGuideHelper? = null
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

    private var mCategorySpinner: Spinner? = null

    private val galleryActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> mGalleryOpenHelper?.updateReadProcess(result) }

    private var mImportHelper: DownloadImportHelper? = null

    override fun getNavCheckedItem(): Int = R.id.nav_downloads

    private fun handleArguments(args: Bundle?): Boolean {
        if (args == null) {
            return false
        }

        if (ACTION_CLEAR_DOWNLOAD_SERVICE == args.getString(KEY_ACTION)) {
            DownloadService.Companion.clear()
        }

        val dm = viewModel.downloadManager
        val gid = args.getLong(KEY_GID, -1L)
        if (gid != -1L) {
            val info = dm.getDownloadInfo(gid)
            if (info != null) {
                viewModel.selectLabel(info.label)
                updateForLabel()
                updateView()

                // Get position
                val list = mList
                if (list != null) {
                    val position = list.indexOf(info)
                    if (position >= 0 && ::mRecyclerView.isInitialized) {
                        mPaginationHelper?.initPage(position, mList, mRecyclerView, mPaginationIndicator)
                    } else {
                        mInitPosition = position
                    }
                }
                return true
            }
        }
        return false
    }

    override fun onNewArguments(args: Bundle) {
        handleArguments(args)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[DownloadsViewModel::class.java]

        val context = ehContext
        AssertUtils.assertNotNull(context)

        // Initialize import helper (must happen before onStart per ActivityResultLauncher contract)
        mImportHelper = DownloadImportHelper(
            requireActivity().activityResultRegistry,
            this,
            contextProvider = { ehContext },
            onFileSelected = { uri ->
                val ctx = ehContext ?: return@DownloadImportHelper
                viewModel.processArchiveImport(ctx, uri)
            }
        )

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
        if (!handleArguments(arguments)) {
            // ViewModel already initializes with the recent label from settings
            updateForLabel()
        }
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

        mCategorySpinner = ViewUtils.`$$`(view, R.id.category_spinner) as Spinner
        mSearchHelper?.initCategorySpinner(mCategorySpinner!!, context)

        mProgressView = ViewUtils.`$$`(view, R.id.download_progress_view) as ProgressView
        val content = ViewUtils.`$$`(view, R.id.content)
        mRecyclerView = ViewUtils.`$$`(content, R.id.recycler_view) as MyEasyRecyclerView
        val fastScroller = ViewUtils.`$$`(content, R.id.fast_scroller) as FastScroller
        mFabLayout = ViewUtils.`$$`(view, R.id.fab_layout) as FabLayout
        val tip = ViewUtils.`$$`(view, R.id.tip) as TextView
        // Initialize helpers
        mGuideHelper = DownloadGuideHelper(this)
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
            ColorDrawable(Color.TRANSPARENT)
        )
        mRecyclerView.setDrawSelectorOnTop(true)
        mRecyclerView.clipToPadding = false
        mRecyclerView.setOnItemClickListener(this)
        mRecyclerView.setOnItemLongClickListener(this)
        mRecyclerView.setChoiceMode(EasyRecyclerView.CHOICE_MODE_MULTIPLE_CUSTOM)
        mRecyclerView.setCustomCheckedListener(mSelectionHelper?.choiceListener)
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

        mGuideHelper?.guide(mRecyclerView, mLayoutManager)
        updatePaginationIndicator()
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateTitle()
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24)

        // Subscribe to Room Flow for reactive download list structure changes.
        // This handles add/remove/state changes persisted to the database.
        // Progress updates (speed, downloaded, total) are @Ignore fields and
        // continue to use the existing DownloadInfoListener callback mechanism.
        collectFlow(viewLifecycleOwner, viewModel.downloadsFlow) { downloads ->
            if (mAdapter == null || searching) {
                return@collectFlow
            }
            // Apply DiffUtil against the last known snapshot
            dispatchDiffUpdate(ArrayList(downloads))
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
            mList = viewModel.downloadList.value
            updateAdapter()
        }

        // Observe category filter list changes
        collectFlow(viewLifecycleOwner, viewModel.listChanged) {
            mList = viewModel.downloadList.value
            dispatchDiffUpdate(mList?.let { ArrayList(it) } ?: ArrayList())
            updateTitle()
            updatePaginationIndicator()
            updateView()
        }

        // Observe import toast events
        collectFlow(viewLifecycleOwner, viewModel.importToast) { resId ->
            Toast.makeText(ehContext ?: return@collectFlow, resId, Toast.LENGTH_SHORT).show()
        }

        // Observe import success events
        collectFlow(viewLifecycleOwner, viewModel.importSuccess) {
            updateForLabel()
            updateView()
        }

        // ── Observe DownloadInfoListener events from ViewModel (sealed dispatch) ──

        collectFlow(viewLifecycleOwner, viewModel.downloadEvent) { event ->
            when (event) {
                is DownloadUiEvent.ItemAdded -> {
                    if (viewModel.currentLabel.value != event.info.label) return@collectFlow
                    mAdapter?.notifyItemInserted(event.position)
                    downloadLabelDraw?.updateDownloadLabels()
                    updateView()
                }
                is DownloadUiEvent.ItemRemoved -> {
                    if (viewModel.currentLabel.value != event.info.label) return@collectFlow
                    mAdapter?.notifyItemRemoved(listIndexInPage(event.position))
                    updateView()
                }
                is DownloadUiEvent.ItemUpdated -> {
                    if (viewModel.currentLabel.value != event.info.label && mList?.contains(event.info) != true) return@collectFlow
                    val index = mList?.indexOf(event.info) ?: return@collectFlow
                    if (index >= 0) {
                        mAdapter?.notifyItemChanged(listIndexInPage(index))
                    }
                }
                is DownloadUiEvent.DiffUpdate -> {
                    val list = mList
                    if (mAdapter != null && list != null) {
                        dispatchDiffUpdate(ArrayList(list))
                    }
                    updateView()
                }
                is DownloadUiEvent.Replaced -> {
                    val list = mList ?: return@collectFlow
                    updateForLabel()
                    updateView()
                    val index = list.indexOf(event.newInfo)
                    if (index >= 0) {
                        mAdapter?.notifyItemChanged(listIndexInPage(index))
                    }
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
                is DownloadUiEvent.Reloaded -> {
                    val list = mList
                    if (mAdapter != null && list != null) {
                        dispatchDiffUpdate(ArrayList(list))
                    }
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

        mGuideHelper?.cleanup()
        mGuideHelper = null
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
        R.id.import_local_archive -> { mImportHelper?.importLocalArchive(); true }
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

    override fun onCreateDrawerView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val draw = downloadLabelDraw ?: DownloadLabelDraw(inflater, container, LabelDrawCallback()).also { downloadLabelDraw = it }
        return draw.createView()
    }

    override fun onBackPressed() {
        if (mGuideHelper?.showcaseView != null) {
            return
        }

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

    override val spiderInfoMap: Map<Long, SpiderInfo>
        get() = viewModel.spiderInfoMap.value

    override val downloadManager: DownloadManager?
        get() = viewModel.downloadManager

    override val recyclerView: EasyRecyclerView?
        get() = if (::mRecyclerView.isInitialized) mRecyclerView else null

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
        override fun getResources(): android.content.res.Resources = this@DownloadsScene.getResources()
        override fun getString(resId: Int): String = this@DownloadsScene.getString(resId)
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

        const val KEY_GID = "gid"
        const val KEY_ACTION = "action"
        private const val KEY_LABEL = "label"

        const val ACTION_CLEAR_DOWNLOAD_SERVICE = "clear_download_service"

        const val LOCAL_GALLERY_INFO_CHANGE = 909
    }
}

/**
 * DiffUtil callback for DownloadInfo lists.
 * Uses gid for identity; compares state, legacy, downloaded, total, speed, thumb for content.
 */
internal class DownloadInfoDiffCallback(
    private val oldList: List<DownloadInfo>,
    private val newList: List<DownloadInfo>
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int = oldList.size

    override fun getNewListSize(): Int = newList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition].gid == newList[newItemPosition].gid
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val oldItem = oldList[oldItemPosition]
        val newItem = newList[newItemPosition]
        return oldItem.state == newItem.state &&
            oldItem.legacy == newItem.legacy &&
            oldItem.downloaded == newItem.downloaded &&
            oldItem.total == newItem.total &&
            oldItem.speed == newItem.speed &&
            ObjectUtils.equal(oldItem.thumb, newItem.thumb)
    }
}
