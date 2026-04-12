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
import android.net.Uri
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.DownloadService
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.ehviewer.ui.GalleryOpenHelper
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
    private var mList: MutableList<DownloadInfo>?
        get() = viewModel.downloadList.value
        set(value) { if (value != null) viewModel.setDownloadList(value) }

    /** Shortcut delegating to [DownloadsViewModel.backList]. */
    private val mBackList: List<DownloadInfo>?
        get() = viewModel.backList.value

    private var mLastSnapshot: MutableList<DownloadInfo> = ArrayList()

    /*---------------
     List pagination
     ---------------*/
    private var myPageChangeListener: MyPageChangeListener? = null

    /*---------------
     View life cycle
     ---------------*/
    private var mRecyclerView: MyEasyRecyclerView? = null
    private var mViewTransition: ViewTransition? = null
    private var mFabLayout: FabLayout? = null
    private var mAdapter: RecyclerView.Adapter<*>? = null
    private var mOriginalAdapter: DownloadAdapter? = null
    private var mLayoutManager: AutoStaggeredGridLayoutManager? = null

    // Helpers
    private var mDragDropHelper: DownloadDragDropHelper? = null
    private var mGuideHelper: DownloadGuideHelper? = null
    private var mPaginationHelper: DownloadPaginationHelper? = null
    private var mSearchHelper: DownloadSearchHelper? = null
    private var mBatchOpsHelper: DownloadBatchOpsHelper? = null

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
    ) { result -> updateReadProcess(result) }

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
                    if (position >= 0 && mRecyclerView != null) {
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

        val newList = if (mList != null) ArrayList(mList!!) else ArrayList()
        val result = DiffUtil.calculateDiff(
            DownloadInfoDiffCallback(mLastSnapshot, newList)
        )
        mLastSnapshot = newList
        mAdapter?.let { result.dispatchUpdatesTo(it) }
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

        // Initialize search + batch-ops helpers
        mSearchHelper = DownloadSearchHelper(SearchHelperCallback())
        mBatchOpsHelper = DownloadBatchOpsHelper(BatchOpsHelperCallback())

        mCategorySpinner = ViewUtils.`$$`(view, R.id.category_spinner) as Spinner
        mSearchHelper!!.initCategorySpinner(mCategorySpinner!!, context)

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
            mPaginationHelper!!.needInitPage = true
        }
        mPaginationIndicator = ViewUtils.`$$`(view, R.id.indicator) as PaginationIndicator

        mPaginationIndicator!!.setPerPageCountChoices(viewModel.perPageCountChoices, mPaginationHelper!!.getPageSizePos(viewModel.pageSize.value))

        mViewTransition = ViewTransition(content, tip)

        val resources = context.resources

        val drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_download)
        drawable?.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        tip.setCompoundDrawables(null, drawable, null, null)
        // Initialize drag-drop via helper
        mOriginalAdapter = DownloadAdapter(this, this)
        mOriginalAdapter!!.setHasStableIds(true)
        mAdapter = mDragDropHelper!!.setup(context, mOriginalAdapter!!)
        mRecyclerView!!.adapter = mAdapter

        // Initialize pagination listener
        val paginationHelper = mPaginationHelper!!
        myPageChangeListener = MyPageChangeListener(
            viewModel.indexPage.value, viewModel.pageSize.value,
            paginationHelper.needInitPage, paginationHelper.doNotScroll,
            mOriginalAdapter, mRecyclerView
        )

        // 设置分页监听器的回调
        myPageChangeListener!!.pageChangeCallback = object : MyPageChangeListener.PageChangeCallback {
            override fun onPageChanged(newIndexPage: Int) {
                viewModel.setIndexPage(newIndexPage)
            }

            override fun onPageSizeChanged(newPageSize: Int) {
                viewModel.setPageSize(newPageSize)
            }
        }
        mLayoutManager = AutoStaggeredGridLayoutManager(0, StaggeredGridLayoutManager.VERTICAL)
        mLayoutManager!!.setColumnSize(resources.getDimensionPixelOffset(AppearanceSettings.getDetailSizeResId()))
        mLayoutManager!!.setStrategy(AutoStaggeredGridLayoutManager.STRATEGY_MIN_SIZE)

        // Configure drag-related RecyclerView properties
        mDragDropHelper?.configureRecyclerView(mRecyclerView!!)

        mRecyclerView!!.setItemViewCacheSize(100)
        mRecyclerView!!.layoutManager = mLayoutManager
        mRecyclerView!!.selector = Ripple.generateRippleDrawable(
            context,
            !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme),
            ColorDrawable(Color.TRANSPARENT)
        )
        mRecyclerView!!.setDrawSelectorOnTop(true)
        mRecyclerView!!.clipToPadding = false
        mRecyclerView!!.setOnItemClickListener(this)
        mRecyclerView!!.setOnItemLongClickListener(this)
        mRecyclerView!!.setChoiceMode(EasyRecyclerView.CHOICE_MODE_MULTIPLE_CUSTOM)
        mRecyclerView!!.setCustomCheckedListener(DownloadChoiceListener())
        val interval = resources.getDimensionPixelOffset(R.dimen.gallery_list_interval)
        val paddingH = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_h)
        val paddingV = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_v)
        val decoration = MarginItemDecoration(interval, paddingH, paddingV, paddingH, paddingV)
        mRecyclerView!!.addItemDecoration(decoration)
        decoration.applyPaddings(mRecyclerView)

        // Attach drag-drop manager to RecyclerView
        mDragDropHelper?.attachToRecyclerView(mRecyclerView!!)

        if (mInitPosition >= 0 && viewModel.indexPage.value != 1) {
            mPaginationHelper?.initPage(mInitPosition, mList, mRecyclerView, mPaginationIndicator)
            mRecyclerView!!.scrollToPosition(listIndexInPage(mInitPosition))
            mInitPosition = -1
        }

        fastScroller.attachToRecyclerView(mRecyclerView)
        val handlerDrawable = HandlerDrawable()
        handlerDrawable.setColor(AttrResources.getAttrColor(context, R.attr.widgetColorThemeAccent))
        fastScroller.setHandlerDrawable(handlerDrawable)
        fastScroller.setOnDragHandlerListener(this)

        mFabLayout!!.setExpanded(false, true)
        mFabLayout!!.setHidePrimaryFab(false)
        mFabLayout!!.setAutoCancel(false)
        mFabLayout!!.setOnClickFabListener(this)
        mFabLayout!!.setOnExpandListener(this)
        mActionFabDrawable = AddDeleteDrawable(context, resources.getColor(R.color.primary_drawable_dark, null))
        mFabLayout!!.primaryFab.setImageDrawable(mActionFabDrawable)
        val fab = mFabLayout!!.getSecondaryFabAt(6)
        if (DRAG_ENABLE) {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_x24, context.theme))
        } else {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_off_x24, context.theme))
        }
        addAboveSnackView(mFabLayout as View)

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
            val newList = ArrayList(downloads)
            val result = DiffUtil.calculateDiff(
                DownloadInfoDiffCallback(mLastSnapshot, newList)
            )
            mLastSnapshot = newList
            result.dispatchUpdatesTo(mAdapter!!)
        }

        // Observe filter loading state
        collectFlow(viewLifecycleOwner, viewModel.filterLoading) { loading ->
            if (loading) {
                mProgressView.visibility = View.VISIBLE
                mRecyclerView?.visibility = View.GONE
            } else {
                mProgressView.visibility = View.GONE
                mRecyclerView?.visibility = View.VISIBLE
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
            val newList = if (mList != null) ArrayList(mList!!) else ArrayList()
            val result = DiffUtil.calculateDiff(
                DownloadInfoDiffCallback(mLastSnapshot, newList)
            )
            mLastSnapshot = newList
            mAdapter?.let { result.dispatchUpdatesTo(it) }
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
                    if (mList !== event.list) return@collectFlow
                    mAdapter?.notifyItemInserted(event.position)
                    downloadLabelDraw?.updateDownloadLabels()
                    updateView()
                }
                is DownloadUiEvent.ItemRemoved -> {
                    if (mList !== event.list) return@collectFlow
                    mAdapter?.notifyItemRemoved(listIndexInPage(event.position))
                    updateView()
                }
                is DownloadUiEvent.ItemUpdated -> {
                    if (mList !== event.list && mList?.contains(event.info) != true) return@collectFlow
                    val index = mList?.indexOf(event.info) ?: return@collectFlow
                    if (index >= 0 && mAdapter != null) {
                        mAdapter!!.notifyItemChanged(listIndexInPage(index))
                    }
                }
                is DownloadUiEvent.DiffUpdate -> {
                    if (mAdapter != null && mList != null) {
                        val newList = ArrayList(mList!!)
                        val result = DiffUtil.calculateDiff(DownloadInfoDiffCallback(mLastSnapshot, newList))
                        mLastSnapshot = newList
                        result.dispatchUpdatesTo(mAdapter!!)
                    }
                    updateView()
                }
                is DownloadUiEvent.Replaced -> {
                    if (mList == null) return@collectFlow
                    updateForLabel()
                    updateView()
                    val index = mList!!.indexOf(event.newInfo)
                    if (index >= 0 && mAdapter != null) {
                        mAdapter!!.notifyItemChanged(listIndexInPage(index))
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
                    if (mAdapter != null && mList != null) {
                        val newList = ArrayList(mList!!)
                        val result = DiffUtil.calculateDiff(DownloadInfoDiffCallback(mLastSnapshot, newList))
                        mLastSnapshot = newList
                        result.dispatchUpdatesTo(mAdapter!!)
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
        if (mLayoutManager != null) {
            val columnWidth = resources.getDimensionPixelOffset(AppearanceSettings.getDetailSizeResId())
            mLayoutManager!!.setColumnSize(columnWidth)
            mRecyclerView?.requestLayout()
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

        if (mRecyclerView != null) {
            mRecyclerView!!.stopScroll()
            mRecyclerView = null
        }
        if (mFabLayout != null) {
            removeAboveSnackView(mFabLayout as View)
            mFabLayout = null
        }

        mRecyclerView = null
        mViewTransition = null
        mAdapter = null
        mOriginalAdapter = null
        mLayoutManager = null
    }

    override fun onNavigationClick(view: View) {
        onBackPressed()
    }

    override fun getMenuResId(): Int = R.menu.scene_download

    @SuppressLint("NonConstantResourceId")
    override fun onMenuItemClick(item: MenuItem): Boolean {
        val activity = activity2
        if (activity == null || mRecyclerView == null || mRecyclerView!!.isInCustomChoice) return false
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
        if (mViewTransition != null) {
            if (mList == null || mList!!.isEmpty()) {
                mViewTransition!!.showView(1)
            } else {
                mViewTransition!!.showView(0)
            }
        }
    }

    override fun onCreateDrawerView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (downloadLabelDraw == null) {
            downloadLabelDraw = DownloadLabelDraw(inflater, container, this)
        }
        return downloadLabelDraw!!.createView()
    }

    override fun onBackPressed() {
        if (mGuideHelper?.showcaseView != null) {
            return
        }

        if (mRecyclerView != null && mRecyclerView!!.isInCustomChoice) {
            mRecyclerView!!.outOfCustomChoiceMode()
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
        if (mRecyclerView != null && !mRecyclerView!!.isInCustomChoice) {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT)
        }
    }

    override fun onItemClick(parent: EasyRecyclerView, view: View, position: Int, id: Long): Boolean {
        val activity = activity2
        val recyclerView = mRecyclerView
        if (activity == null || recyclerView == null) {
            return false
        }

        if (recyclerView.isInCustomChoice) {
            recyclerView.toggleItemChecked(position)
            return true
        } else {
            val list = mList ?: return false
            if (position < 0 || position >= list.size) {
                return false
            }

            val downloadInfo = list[positionInList(position)]
            var intent = Intent(activity, GalleryActivity::class.java)
            // Check if this is an imported archive
            if (downloadInfo.archiveUri != null && downloadInfo.archiveUri!!.startsWith("content://")) {
                // This is an imported archive, ensure URI permission is available
                val archiveUri = Uri.parse(downloadInfo.archiveUri)
                try {
                    // Test if we can access the URI
                    ehContext!!.contentResolver.openInputStream(archiveUri)?.use { testStream ->
                        @Suppress("SENSELESS_COMPARISON")
                        if (testStream == null) {
                            Toast.makeText(ehContext, R.string.archive_not_accessible, Toast.LENGTH_SHORT).show()
                            return true
                        }
                    }
                } catch (e: SecurityException) {
                    // Try to restore permission
                    try {
                        ehContext!!.contentResolver.takePersistableUriPermission(
                            archiveUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (ex: Exception) {
                        Toast.makeText(ehContext, R.string.archive_permission_lost, Toast.LENGTH_LONG).show()
                        Analytics.recordException(ex)
                        return true
                    }
                } catch (e: Exception) {
                    Toast.makeText(ehContext, R.string.archive_not_accessible, Toast.LENGTH_SHORT).show()
                    return true
                }
                intent.action = Intent.ACTION_VIEW
                intent.data = archiveUri
            } else {
                // Use GalleryOpenHelper to prefer local files over server
                // buildReadIntent is suspend (resolves download dir from DB)
                viewLifecycleOwner.lifecycleScope.launch {
                    val readIntent = withContext(Dispatchers.IO) {
                        GalleryOpenHelper.buildReadIntent(activity, downloadInfo)
                    }
                    galleryActivityLauncher.launch(readIntent)
                }
            }
            return true
        }
    }

    override fun onItemLongClick(parent: EasyRecyclerView, view: View, position: Int, id: Long): Boolean {
        val recyclerView = mRecyclerView ?: return false

        if (!recyclerView.isInCustomChoice) {
            recyclerView.intoCustomChoiceMode()
        }
        recyclerView.toggleItemChecked(position)

        return true
    }

    @SuppressLint("RtlHardcoded")
    override fun onExpand(expanded: Boolean) {
        val actionFabDrawable = mActionFabDrawable ?: return

        if (expanded) {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT)
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT)
            actionFabDrawable.setDelete(ANIMATE_TIME)
        } else {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.LEFT)
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT)
            actionFabDrawable.setAdd(ANIMATE_TIME)
        }
    }

    override fun onClickPrimaryFab(view: FabLayout, fab: FloatingActionButton?) {
        if (mRecyclerView != null && mRecyclerView!!.isInCustomChoice) {
            mRecyclerView!!.outOfCustomChoiceMode()
            return
        }
        if (mRecyclerView != null && !mRecyclerView!!.isInCustomChoice) {
            mRecyclerView!!.intoCustomChoiceMode()
            return
        }
        view.toggle()
    }

    override fun onClickSecondaryFab(view: FabLayout, fab: FloatingActionButton, position: Int) {
        mBatchOpsHelper?.onClickSecondaryFab(fab, position)
    }

    /**
     * Returns the DownloadManager instance. Called from DownloadLabelDraw.kt.
     */
    fun getMDownloadManager(): DownloadManager = viewModel.downloadManager

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

    override val list: MutableList<DownloadInfo>?
        get() = mList

    override val spiderInfoMap: Map<Long, SpiderInfo>
        get() = viewModel.spiderInfoMap.value

    override val downloadManager: DownloadManager?
        get() = viewModel.downloadManager

    override val recyclerView: EasyRecyclerView?
        get() = mRecyclerView

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
        mOriginalAdapter = DownloadAdapter(this, this)
        mOriginalAdapter!!.setHasStableIds(true)
        // 避免重复创建包装适配器，直接使用原始适配器
        mAdapter = mOriginalAdapter
        mRecyclerView?.adapter = mAdapter
    }

    override fun onSearchEditTextBackPressed() {
        mSearchHelper?.onSearchEditTextBackPressed()
    }

    override fun onStateChange(searchBar: SearchBar, newState: Int, oldState: Int, animation: Boolean) {
        // No-op
    }

    override fun isValidView(recyclerView: RecyclerView): Boolean = false

    override fun getValidRecyclerView(): RecyclerView? = mRecyclerView

    override fun forceShowSearchBar(): Boolean = false


    private fun updateReadProcess(result: androidx.activity.result.ActivityResult) {
        if (result.resultCode == LOCAL_GALLERY_INFO_CHANGE) {
            val data = result.data
            if (data != null) {
                @Suppress("DEPRECATION")
                val info = data.getParcelableExtra<GalleryInfo>("info")

                // Check if this is an imported archive - skip SpiderInfo processing
                var isImportedArchive = false
                if (info is DownloadInfo) {
                    isImportedArchive = info.archiveUri != null &&
                        info.archiveUri!!.startsWith("content://")
                }

                if (!isImportedArchive && info != null) {
                    // Only process SpiderInfo for regular downloads, not imported archives
                    viewModel.removeSpiderInfo(info.gid)
                    val gid = info.gid
                    viewLifecycleOwner.lifecycleScope.launch {
                        val spiderInfo = withContext(Dispatchers.IO) {
                            SpiderInfo.getSpiderInfo(info)
                        }
                        if (spiderInfo != null) {
                            viewModel.putSpiderInfo(gid, spiderInfo)
                        }
                    }
                }

                var position = -1
                if (mList == null || mAdapter == null || info == null) {
                    return
                }
                for (i in mList!!.indices) {
                    if (mList!![i].gid == info.gid) {
                        position = listIndexInPage(i)
                        break
                    }
                }
                if (position != -1) {
                    mAdapter!!.notifyItemChanged(position)
                }
                // If item not found in current page, no notification needed
            }
        }
    }

    fun runOnUiThread(runnable: Runnable) {
        activity2?.runOnUiThread(runnable)
    }

    private inner class DownloadChoiceListener : EasyRecyclerView.CustomChoiceListener {

        override fun onIntoCustomChoice(view: EasyRecyclerView) {
            if (mRecyclerView != null) {
                mRecyclerView!!.setOnItemLongClickListener(null)
                mRecyclerView!!.isLongClickable = false
            }
            mFabLayout?.setExpanded(true)
            // Lock drawer
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT)
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT)
        }

        override fun onOutOfCustomChoice(view: EasyRecyclerView) {
            mRecyclerView?.setOnItemLongClickListener(this@DownloadsScene)
            mFabLayout?.setExpanded(false)
            // Unlock drawer
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.LEFT)
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT)
        }

        override fun onItemCheckedStateChanged(view: EasyRecyclerView, position: Int, id: Long, checked: Boolean) {
            if (view.checkedItemCount == 0) {
                view.outOfCustomChoiceMode()
            }
        }
    }

    // ── Callback implementations for helpers ──

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
        override val mRecyclerView: EasyRecyclerView? get() = this@DownloadsScene.mRecyclerView
        override val mFabLayout: FabLayout? get() = this@DownloadsScene.mFabLayout
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

        private const val ANIMATE_TIME = 300L
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
