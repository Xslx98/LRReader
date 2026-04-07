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
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.NinePatchDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.github.amlcurran.showcaseview.ShowcaseView
import com.github.amlcurran.showcaseview.SimpleShowcaseEventListener
import com.github.amlcurran.showcaseview.targets.PointTarget
import com.github.amlcurran.showcaseview.targets.ViewTarget
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.h6ah4i.android.widget.advrecyclerview.animator.DraggableItemAnimator
import com.h6ah4i.android.widget.advrecyclerview.animator.GeneralItemAnimator
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager
import com.hippo.android.resource.AttrResources
import com.hippo.drawable.AddDeleteDrawable
import com.hippo.drawerlayout.DrawerLayout
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.easyrecyclerview.FastScroller
import com.hippo.easyrecyclerview.HandlerDrawable
import com.hippo.easyrecyclerview.MarginItemDecoration
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadInfoListener
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.DownloadService
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.ehviewer.ui.GalleryOpenHelper
import com.hippo.ehviewer.ui.annotation.ViewLifeCircle
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
    DownloadInfoListener,
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
    private var _downloadManager: DownloadManager? = null

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

    // 拖拽管理器
    private var mDragDropManager: RecyclerViewDragDropManager? = null

    private var mShowcaseView: ShowcaseView? = null

    private lateinit var mProgressView: ProgressView

    private var mSearchDialog: AlertDialog? = null
    private var mSearchBar: SearchBar? = null
    private var mPaginationIndicator: PaginationIndicator? = null

    private var downloadLabelDraw: DownloadLabelDraw? = null

    @ViewLifeCircle
    private var mSearchBarMover: SearchBarMover? = null
    private var mSearchMode = false

    var searchKey: String?
        get() = viewModel.searchKey.value
        set(value) { viewModel.setSearchKey(value) }

    private var mInitPosition = -1

    var searching: Boolean
        get() = viewModel.searching.value
        set(value) { viewModel.setSearching(value) }
    private var doNotScroll = false

    private var needInitPage = false
    private var needInitPageSize = false

    private var mCategorySpinner: Spinner? = null

    private val galleryActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> updateReadProcess(result) }

    private var mImportHelper: DownloadImportHelper? = null
    private var mLabelHelper: DownloadLabelHelper? = null
    private var mFilterHelper: DownloadFilterHelper? = null

    override fun getNavCheckedItem(): Int = R.id.nav_downloads

    private fun handleArguments(args: Bundle?): Boolean {
        if (args == null) {
            return false
        }

        if (ACTION_CLEAR_DOWNLOAD_SERVICE == args.getString(KEY_ACTION)) {
            DownloadService.Companion.clear()
        }

        val dm = _downloadManager
        if (dm != null) {
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
                            initPage(position)
                        } else {
                            mInitPosition = position
                        }
                    }
                    return true
                }
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
        _downloadManager = viewModel.downloadManager
        _downloadManager!!.addDownloadInfoListener(this)

        // Initialize import helper (must happen before onStart per ActivityResultLauncher contract)
        mImportHelper = DownloadImportHelper(
            object : DownloadImportHelper.Callback {
                override fun getContext() = ehContext
                override fun getActivity() = activity2
                override fun getDownloadManager() = _downloadManager
                override fun getString(resId: Int) = this@DownloadsScene.getString(resId)
                override fun onImportSuccess() {
                    updateForLabel()
                    updateView()
                }
            },
            requireActivity().activityResultRegistry,
            this
        )

        // Initialize label helper for bulk actions (start/stop/delete/move)
        mLabelHelper = DownloadLabelHelper(
            object : DownloadLabelHelper.Callback {
                override fun getContext() = ehContext
                override fun getActivity() = activity2
                override fun getString(resId: Int) = this@DownloadsScene.getString(resId)
                override fun getString(resId: Int, vararg formatArgs: Any) =
                    this@DownloadsScene.getString(resId, *formatArgs)
                override fun getDownloadManager() = _downloadManager
                override fun getList() = mList
                override fun getCheckedItemPositions() = mRecyclerView?.checkedItemPositions
                override fun positionInList(position: Int) = this@DownloadsScene.positionInList(position)
                override fun exitCustomChoiceMode() { mRecyclerView?.outOfCustomChoiceMode() }
                override fun onClickPrimaryFabForRandom() {
                    mFabLayout?.let { onClickPrimaryFab(it, null) }
                }
                override fun viewRandom() { this@DownloadsScene.viewRandom() }
                override fun setDragEnable(fab: com.google.android.material.floatingactionbutton.FloatingActionButton) {
                    this@DownloadsScene.setDragEnable(fab)
                }
            }
        )

        // Initialize filter helper for category filter, sort, and search
        mFilterHelper = DownloadFilterHelper(
            object : DownloadFilterHelper.Callback {
                override fun getContext() = ehContext
                override fun getString(resId: Int) = this@DownloadsScene.getString(resId)
                override fun getList() = mList
                override fun getBackList() = mBackList
                override fun getDownloadManager() = _downloadManager
                override fun setList(list: MutableList<DownloadInfo>) { mList = list }
                override fun isAdded() = this@DownloadsScene.isAdded
                override fun isSearching() = searching
                override fun setSearching(searching: Boolean) {
                    this@DownloadsScene.searching = searching
                }
                override fun showProgress() {
                    mProgressView.visibility = View.VISIBLE
                    mRecyclerView?.visibility = View.GONE
                }
                override fun hideProgress() {
                    mProgressView.visibility = View.GONE
                    mRecyclerView?.visibility = View.VISIBLE
                }
                override fun updateAdapter() { this@DownloadsScene.updateAdapter() }
                override fun updateTitle() { this@DownloadsScene.updateTitle() }
                override fun updatePaginationIndicator() { this@DownloadsScene.updatePaginationIndicator() }
                override fun updateView() { this@DownloadsScene.updateView() }
                override fun queryUnreadSpiderInfo() { this@DownloadsScene.queryUnreadSpiderInfo() }
                @SuppressLint("NotifyDataSetChanged")
                override fun notifyListChanged() {
                    mAdapter?.notifyDataSetChanged()
                    mLastSnapshot = if (mList != null) ArrayList(mList!!) else ArrayList()
                }
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

        var manager = _downloadManager
        if (manager == null) {
            val context = ehContext
            if (context != null) {
                manager = viewModel.downloadManager
            }
        } else {
            _downloadManager = null
        }

        if (manager != null) {
            manager.removeDownloadInfoListener(this)
        } else {
            Log.e(TAG, "Can't removeDownloadInfoListener")
        }
        mActionFabDrawable = null
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateForLabel() {
        viewModel.updateForLabel()

        mAdapter?.notifyDataSetChanged()
        mLastSnapshot = if (mList != null) ArrayList(mList!!) else ArrayList()
        updateTitle()
        updatePaginationIndicator()
        queryUnreadSpiderInfo()
    }

    private fun updatePaginationIndicator() {
        val indicator = mPaginationIndicator ?: return
        val list = mList ?: return
        val paginationSize = viewModel.paginationSize
        val canPagination = viewModel.canPagination
        val pageSize = viewModel.pageSize.value
        val indexPage = viewModel.indexPage.value
        if (list.size < paginationSize || !canPagination) {
            indicator.visibility = View.GONE
            return
        }
        indicator.visibility = View.VISIBLE
        needInitPageSize = true
        indicator.initPaginationIndicator(pageSize, viewModel.perPageCountChoices, list.size, indexPage)
        indicator.setListener(myPageChangeListener)

        // 同步分页监听器的状态
        myPageChangeListener?.let {
            it.indexPage = indexPage
            it.pageSize = pageSize
            it.isNeedInitPage = needInitPage
            it.isDoNotScroll = doNotScroll
        }
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

        mCategorySpinner = ViewUtils.`$$`(view, R.id.category_spinner) as Spinner
        mFilterHelper?.initCategorySpinner(mCategorySpinner!!, context)

        mProgressView = ViewUtils.`$$`(view, R.id.download_progress_view) as ProgressView
        val content = ViewUtils.`$$`(view, R.id.content)
        mRecyclerView = ViewUtils.`$$`(content, R.id.recycler_view) as MyEasyRecyclerView
        val fastScroller = ViewUtils.`$$`(content, R.id.fast_scroller) as FastScroller
        mFabLayout = ViewUtils.`$$`(view, R.id.fab_layout) as FabLayout
        val tip = ViewUtils.`$$`(view, R.id.tip) as TextView
        if (mPaginationIndicator != null) {
            needInitPage = true
        }
        mPaginationIndicator = ViewUtils.`$$`(view, R.id.indicator) as PaginationIndicator

        mPaginationIndicator!!.setPerPageCountChoices(viewModel.perPageCountChoices, getPageSizePos(viewModel.pageSize.value))

        mViewTransition = ViewTransition(content, tip)

        val resources = context.resources

        val drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_download)
        drawable?.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        tip.setCompoundDrawables(null, drawable, null, null)
        // 初始化拖拽管理器
        mDragDropManager = RecyclerViewDragDropManager()
        try {
            mDragDropManager!!.setDraggingItemShadowDrawable(
                context.resources.getDrawable(R.drawable.shadow_8dp) as NinePatchDrawable
            )
        } catch (e: Exception) {
            // 忽略硬件位图相关错误
            Log.w("DownloadsScene", "Error setting drag shadow: ${e.message}")
        }

        mOriginalAdapter = DownloadAdapter(this, this)
        mOriginalAdapter!!.setHasStableIds(true)
        mAdapter = mDragDropManager!!.createWrappedAdapter(mOriginalAdapter!!) // 包装适配器以支持拖拽
        mDragDropManager!!.isCheckCanDropEnabled = false
        mRecyclerView!!.adapter = mAdapter

        // 初始化分页监听器
        myPageChangeListener = MyPageChangeListener(
            viewModel.indexPage.value, viewModel.pageSize.value, needInitPage, doNotScroll, mOriginalAdapter, mRecyclerView
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

        // 设置拖拽动画器
        val animator: GeneralItemAnimator = DraggableItemAnimator()
        mRecyclerView!!.itemAnimator = animator

        mRecyclerView!!.setItemViewCacheSize(100)
        try {
            mRecyclerView!!.isDrawingCacheEnabled = true
            mRecyclerView!!.drawingCacheQuality = View.DRAWING_CACHE_QUALITY_HIGH
        } catch (e: Exception) {
            // 忽略硬件位图相关错误
            Log.w("DownloadsScene", "Error setting drawing cache: ${e.message}")
        }
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
        // Cancel change animation
        val itemAnimator = mRecyclerView!!.itemAnimator
        if (itemAnimator is GeneralItemAnimator) {
            itemAnimator.setSupportsChangeAnimations(false)
        }
        val interval = resources.getDimensionPixelOffset(R.dimen.gallery_list_interval)
        val paddingH = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_h)
        val paddingV = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_v)
        val decoration = MarginItemDecoration(interval, paddingH, paddingV, paddingH, paddingV)
        mRecyclerView!!.addItemDecoration(decoration)
        decoration.applyPaddings(mRecyclerView)

        // 将拖拽管理器附加到RecyclerView
        if (mDragDropManager != null) {
            try {
                mDragDropManager!!.attachRecyclerView(mRecyclerView!!)
            } catch (e: Exception) {
                // 忽略硬件位图相关错误
                Log.w("DownloadsScene", "Error attaching drag manager: ${e.message}")
            }
        }

        if (mInitPosition >= 0 && viewModel.indexPage.value != 1) {
            initPage(mInitPosition)
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

        guide()
        updatePaginationIndicator()
        return view
    }

    private fun guide() {
        if (Settings.getGuideDownloadThumb() && mRecyclerView != null) {
            mRecyclerView!!.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (Settings.getGuideDownloadThumb()) {
                        guideDownloadThumb()
                    }
                    if (mRecyclerView != null) {
                        ViewUtils.removeOnGlobalLayoutListener(mRecyclerView!!.viewTreeObserver, this)
                    }
                }
            })
        } else {
            guideDownloadLabels()
        }
    }

    @Suppress("DEPRECATION")
    private fun guideDownloadThumb() {
        val activity = activity2
        if (activity == null || !Settings.getGuideDownloadThumb() || mLayoutManager == null || mRecyclerView == null) {
            guideDownloadLabels()
            return
        }
        val position = mLayoutManager!!.findFirstCompletelyVisibleItemPositions(null)[0]
        if (position < 0) {
            guideDownloadLabels()
            return
        }
        val holder = mRecyclerView!!.findViewHolderForAdapterPosition(position)
        if (holder == null) {
            guideDownloadLabels()
            return
        }

        mShowcaseView = ShowcaseView.Builder(activity)
            .withMaterialShowcase()
            .setStyle(R.style.Guide)
            .setTarget(ViewTarget((holder as DownloadAdapter.DownloadHolder).thumb))
            .blockAllTouches()
            .setContentTitle(R.string.guide_download_thumb_title)
            .setContentText(R.string.guide_download_thumb_text)
            .replaceEndButton(R.layout.button_guide)
            .setShowcaseEventListener(object : SimpleShowcaseEventListener() {
                override fun onShowcaseViewDidHide(showcaseView: ShowcaseView) {
                    mShowcaseView = null
                    ViewUtils.removeFromParent(showcaseView)
                    Settings.putGuideDownloadThumb(false)
                    guideDownloadLabels()
                }
            }).build()
    }

    @Suppress("DEPRECATION")
    private fun guideDownloadLabels() {
        val activity = activity2
        if (activity == null || !Settings.getGuideDownloadLabels()) {
            return
        }

        val display = activity.windowManager.defaultDisplay
        val point = Point()
        display.getSize(point)

        mShowcaseView = ShowcaseView.Builder(activity)
            .withMaterialShowcase()
            .setStyle(R.style.Guide)
            .setTarget(PointTarget(point.x, point.y / 3))
            .blockAllTouches()
            .setContentTitle(R.string.guide_download_labels_title)
            .setContentText(R.string.guide_download_labels_text)
            .replaceEndButton(R.layout.button_guide)
            .setShowcaseEventListener(object : SimpleShowcaseEventListener() {
                override fun onShowcaseViewDidHide(showcaseView: ShowcaseView) {
                    mShowcaseView = null
                    ViewUtils.removeFromParent(showcaseView)
                    Settings.puttGuideDownloadLabels(false)
                    openDrawer(Gravity.RIGHT)
                }
            }).build()
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

        if (mShowcaseView != null) {
            ViewUtils.removeFromParent(mShowcaseView)
            mShowcaseView = null
        }
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
        mDragDropManager = null
    }

    override fun onNavigationClick(view: View) {
        onBackPressed()
    }

    override fun getMenuResId(): Int = R.menu.scene_download

    @SuppressLint("NonConstantResourceId")
    override fun onMenuItemClick(item: MenuItem): Boolean {
        // Skip when in choice mode
        val activity = activity2
        if (activity == null || mRecyclerView == null || mRecyclerView!!.isInCustomChoice) {
            return false
        }

        when (item.itemId) {
            R.id.action_start_all -> {
                val intent = Intent(activity, DownloadService::class.java)
                intent.action = DownloadService.ACTION_START_ALL
                activity.startService(intent)
                return true
            }
            R.id.action_stop_all -> {
                _downloadManager?.stopAllDownload()
                return true
            }
            R.id.action_reset_reading_progress -> {
                val context = ehContext ?: return false
                if (searching) {
                    Toast.makeText(context, R.string.download_searching, Toast.LENGTH_LONG).show()
                    return true
                }
                AlertDialog.Builder(context)
                    .setMessage(R.string.reset_reading_progress_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        _downloadManager?.resetAllReadingProgress()
                    }.show()
                return true
            }
            R.id.search_download_gallery -> {
                val context = ehContext ?: return false
                gotoSearch(context)
                return true
            }
            R.id.all,
            R.id.sort_by_default,
            R.id.download_done,
            R.id.not_started,
            R.id.waiting,
            R.id.downloading,
            R.id.failed,
            R.id.sort_by_gallery_id_asc,
            R.id.sort_by_gallery_id_desc,
            R.id.sort_by_create_time_asc,
            R.id.sort_by_create_time_desc,
            R.id.sort_by_rating_asc,
            R.id.sort_by_rating_desc,
            R.id.sort_by_name_asc,
            R.id.sort_by_name_desc,
            R.id.sort_by_file_size_asc,
            R.id.sort_by_file_size_desc -> {
                gotoFilterAndSort(item.itemId)
                return true
            }
            R.id.import_local_archive -> {
                mImportHelper?.importLocalArchive()
                return true
            }
        }
        return false
    }

    private fun gotoSearch(context: android.content.Context) {
        if (mSearchDialog != null) {
            mSearchDialog!!.show()
            return
        }
        val layoutInflater = LayoutInflater.from(context)

        val drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_download)

        val linearLayout = layoutInflater.inflate(R.layout.download_search_dialog, null) as LinearLayout
        val searchBar = linearLayout.findViewById<SearchBar>(R.id.download_search_bar)
        mSearchBar = searchBar
        searchBar.setHelper(this)
        searchBar.setIsComeFromDownload(true)
        searchBar.setEditTextHint(R.string.download_search_hint)
        searchBar.setLeftDrawable(drawable)
        searchBar.setText(searchKey)
        if (!searchKey.isNullOrEmpty()) {
            searchBar.setTitle(searchKey)
            searchBar.cursorToEnd()
        } else {
            searchBar.setTitle(R.string.download_search_hint)
        }

        searchBar.setRightDrawable(DrawableManager.getVectorDrawable(context, R.drawable.v_magnify_x24))
        mSearchBarMover = SearchBarMover(this, searchBar)
        mSearchDialog = AlertDialog.Builder(context)
            .setMessage(R.string.download_search_gallery)
            .setView(linearLayout)
            .setCancelable(true)
            .setOnDismissListener { onSearchDialogDismiss(it) }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                searchKey = null
                searchBar.setText(null)
                searchBar.setTitle(null as String?)
                searchBar.applySearch(true)
                dialog.dismiss()
            }
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                searchBar.applySearch(true)
                dialog.dismiss()
            }.show()
    }

    private fun onSearchDialogDismiss(@Suppress("UNUSED_PARAMETER") dialog: DialogInterface) {
        mSearchMode = false
    }

    private fun enterSearchMode(animation: Boolean) {
        val searchBar = mSearchBar
        if (mSearchMode || searchBar == null || mSearchBarMover == null) {
            return
        }
        mSearchMode = true
        searchBar.setState(SearchBar.STATE_SEARCH_LIST, animation)
        mSearchBarMover!!.returnSearchBarPosition(animation)
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
        if (mShowcaseView != null) {
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
                intent = GalleryOpenHelper.buildReadIntent(activity, downloadInfo)
            }
            galleryActivityLauncher.launch(intent)
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
        val recyclerView = mRecyclerView ?: return
        if (ehContext == null || activity2 == null) return

        if (position == 0) {
            recyclerView.checkAll()
        } else {
            mLabelHelper?.handleSecondaryFabAction(position, fab)
        }
    }

    private fun setDragEnable(fab: FloatingActionButton) {
        DRAG_ENABLE = !DRAG_ENABLE
        DownloadSettings.setDragDownloadGallery(DRAG_ENABLE)
        val context = ehContext ?: return
        if (DRAG_ENABLE) {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_x24, context.theme))
        } else {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_off_x24, context.theme))
        }
    }

    private fun viewRandom() {
        val list = mList ?: return
        val position = (Math.random() * list.size).toInt()
        if (position < 0 || position >= list.size) {
            return
        }
        val activity = activity2
        if (activity == null || mRecyclerView == null) {
            return
        }

        val intent = Intent(activity, GalleryActivity::class.java)
        intent.action = GalleryActivity.ACTION_LRR
        intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, list[position] as GalleryInfo)
        galleryActivityLauncher.launch(intent)
    }

    override fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
        if (mList !== list) {
            return
        }
        mAdapter?.notifyItemInserted(position)
        downloadLabelDraw?.updateDownloadLabels()
        updateView()
    }

    override fun onReplace(newInfo: DownloadInfo, oldInfo: DownloadInfo) {
        if (mList == null) {
            return
        }
        updateForLabel()
        updateView()

        val index = mList!!.indexOf(newInfo)
        if (index >= 0 && mAdapter != null) {
            mAdapter!!.notifyItemChanged(listIndexInPage(index))
        }
    }

    override fun onUpdate(info: DownloadInfo, list: List<DownloadInfo>, mWaitList: List<DownloadInfo>) {
        if (mList !== list && !mList!!.contains(info)) {
            return
        }
        val index = mList!!.indexOf(info)
        if (index >= 0 && mAdapter != null) {
            mAdapter!!.notifyItemChanged(listIndexInPage(index))
        }
    }

    override fun onUpdateAll() {
        if (mAdapter != null && mList != null) {
            val newList = ArrayList(mList!!)
            val result = DiffUtil.calculateDiff(
                DownloadInfoDiffCallback(mLastSnapshot, newList)
            )
            mLastSnapshot = newList
            result.dispatchUpdatesTo(mAdapter!!)
        }
    }

    override fun onReload() {
        if (mAdapter != null && mList != null) {
            val newList = ArrayList(mList!!)
            val result = DiffUtil.calculateDiff(
                DownloadInfoDiffCallback(mLastSnapshot, newList)
            )
            mLastSnapshot = newList
            result.dispatchUpdatesTo(mAdapter!!)
        }
        updateView()
    }

    override fun onChange() {
        viewModel.resetToDefaultLabel()
        updateForLabel()
        updateView()
    }

    override fun onRenameLabel(from: String, to: String) {
        if (!ObjectUtils.equal(viewModel.currentLabel.value, from)) {
            return
        }
        viewModel.onLabelRenamed(from, to)
        updateForLabel()
        updateView()
    }

    override fun onRemove(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
        if (mList !== list) {
            return
        }
        mAdapter?.notifyItemRemoved(listIndexInPage(position))
        updateView()
    }

    override fun onUpdateLabels() {
        // No-op: label updates are handled by DownloadLabelDraw
    }

    /**
     * Returns the DownloadManager instance. Called from DownloadLabelDraw.java.
     */
    fun getMDownloadManager(): DownloadManager? = _downloadManager

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
        get() = _downloadManager

    override val recyclerView: EasyRecyclerView?
        get() = mRecyclerView

    override fun onClickTitle() {
        if (!mSearchMode) {
            enterSearchMode(true)
        }
    }

    override fun onClickLeftIcon() {
        // No-op
    }

    override fun onClickRightIcon() {
        mSearchBar?.applySearch(true)
    }

    override fun onSearchEditTextClick() {
        // No-op
    }

    override fun onApplySearch(query: String) {
        searchKey = query
        mSearchBar?.hideKeyBoard()
        searching = true
        startSearching()
    }

    internal fun startSearching() {
        if (mSearchMode) {
            mSearchMode = false
            mSearchBar?.setTitle(searchKey)
            mSearchBar?.setState(SearchBar.STATE_NORMAL)
        }

        mSearchDialog!!.dismiss()

        updateForLabel()

        mFilterHelper?.startSearching(searchKey ?: "")
    }

    private fun gotoFilterAndSort(id: Int) {
        mFilterHelper?.gotoFilterAndSort(id)
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
        if (mSearchMode) {
            mSearchMode = false
        }
        mSearchBar?.setState(SearchBar.STATE_NORMAL, true)
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
                    val spiderInfo = SpiderInfo.getSpiderInfo(info)
                    if (spiderInfo != null) {
                        viewModel.putSpiderInfo(info.gid, spiderInfo)
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

    private fun queryUnreadSpiderInfo() {
        // E-Hentai SpiderInfo sync removed -- no-op
    }

    private fun spiderInfoResultCallBack(resultMap: Map<Long, SpiderInfo>) {
        viewModel.putAllSpiderInfo(resultMap)
        val adapter = mAdapter ?: return
        val list = mList ?: return
        // Content-only change: spider info updated, notify affected items
        for (i in list.indices) {
            if (resultMap.containsKey(list[i].gid)) {
                adapter.notifyItemChanged(i)
            }
        }
    }

    private fun initPage(position: Int) {
        if (mList != null && mList!!.size > viewModel.paginationSize && viewModel.canPagination) {
            viewModel.setIndexPage(position / viewModel.pageSize.value + 1)
        }
        doNotScroll = true
        mPaginationIndicator?.skip2Pos(viewModel.indexPage.value)
        mRecyclerView!!.scrollToPosition(viewModel.listIndexInPage(position))
    }

    private fun getPageSizePos(pageSize: Int): Int {
        var index = 0
        for (i in viewModel.perPageCountChoices.indices) {
            if (pageSize == viewModel.perPageCountChoices[i]) {
                index = i
                break
            }
        }
        return index
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

    /**
     * DiffUtil callback for DownloadInfo lists.
     * Uses gid for identity; compares state, legacy, downloaded, total, speed, thumb for content.
     */
    private class DownloadInfoDiffCallback(
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
