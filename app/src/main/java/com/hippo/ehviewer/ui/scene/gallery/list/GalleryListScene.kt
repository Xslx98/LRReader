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

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Point
import androidx.core.graphics.drawable.toDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import androidx.activity.result.contract.ActivityResultContracts
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.github.amlcurran.showcaseview.ShowcaseView
import com.github.amlcurran.showcaseview.SimpleShowcaseEventListener
import com.github.amlcurran.showcaseview.targets.PointTarget
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.hippo.android.resource.AttrResources
import com.hippo.drawable.AddDeleteDrawable
import com.hippo.drawable.DrawerArrowDrawable
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.LRRUtils

import com.lanraragi.reader.domain.Archive
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.dao.QuickSearch
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.event.AppEventBus
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.settings.GuideSettings
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene
import com.hippo.ehviewer.widget.SearchBar
import com.hippo.ehviewer.widget.SearchLayout
import com.hippo.lib.yorozuya.AssertUtils
import com.hippo.lib.yorozuya.ViewUtils
import com.hippo.refreshlayout.RefreshLayout
import com.hippo.ripple.Ripple
import com.hippo.scene.Announcer
import com.hippo.ehviewer.util.collectFlow
import com.hippo.view.ViewTransition
import com.hippo.widget.ContentLayout
import com.hippo.widget.FabLayout
import com.hippo.widget.SearchBarMover
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.friendlyError

class GalleryListScene : BaseScene(),
    EasyRecyclerView.OnItemClickListener, EasyRecyclerView.OnItemLongClickListener,
    View.OnClickListener {

    /*---------------
     Whole life cycle
     ---------------*/
    @JvmField
    var mUrlBuilder: ListUrlBuilder? = null

    /*---------------
     View life cycle — internal visibility for GalleryListHelperFactory
     ---------------*/
    internal lateinit var recyclerView: EasyRecyclerView
    internal lateinit var searchLayout: SearchLayout
    internal lateinit var searchBar: SearchBar
    internal lateinit var searchFab: View
    internal lateinit var fabLayout: FabLayout
    internal var floatingActionButton: FloatingActionButton? = null
    internal var viewTransition: ViewTransition? = null
    private var mAdapterImpl: GalleryListAdapter? = null

    /** Exposes the adapter as its base type so GalleryListHelperFactory can call notify methods. */
    internal val adapter: GalleryAdapterNew? get() = mAdapterImpl
    @JvmField
    var mHelper: GalleryListDataHelper? = null
    internal var leftDrawable: DrawerArrowDrawable? = null
    internal var rightDrawable: AddDeleteDrawable? = null
    internal var searchBarMover: SearchBarMover? = null
    internal var actionFabDrawable: AddDeleteDrawable? = null

    private var mHideActionFabSlop = 0

    private var mHasFirstRefresh = false
    private var mLastServerConfigVersion = LRRAuthManager.serverConfigVersion
    private var mNavCheckedId = 0
    private var mPressBackTime: Long = 0
    private var mRestoredState = GalleryStateHelper.STATE_NORMAL

    /**
     * arcids deleted while this scene was off-screen (covered by the detail page).
     * Drained in [onResume] so the RecyclerView remove + reflow animation plays
     * after the user is actually looking at the list, not while it's hidden.
     */
    private val pendingDeletedArcIds = mutableSetOf<String>()

    private var mShowcaseView: ShowcaseView? = null
    internal lateinit var downloadManager: DownloadManager
        private set

    private lateinit var viewModel: GalleryListViewModel

    /*---------------
     Extracted helpers — internal visibility for GalleryListHelperFactory
     ---------------*/
    internal var filterHelper: GalleryFilterHelper? = null
    internal var stateHelper: GalleryStateHelper? = null
    internal var tagChipHelper: GalleryTagChipHelper? = null
    internal var itemActionHelper: GalleryItemActionHelper? = null
    internal var uploadHelper: GalleryUploadHelper? = null
    private var mSearchHelper: GallerySearchHelper? = null
    internal var listSearchHelper: GalleryListSearchHelper? = null
    private var mDrawerHelper: GalleryDrawerHelper? = null
    private var mFabHelper: GalleryFabHelper? = null
    private var searchBarHelper: GallerySearchBarHelper? = null

    /** Determinate archive-upload progress dialog (mirrors the in-app update style). */
    private var uploadProgressDialog: AlertDialog? = null
    private var uploadProgressBar: LinearProgressIndicator? = null
    private var uploadProgressTitle: TextView? = null
    private var uploadProgressPercent: TextView? = null

    /**
     * Stored Uri-callback for the in-flight pick. Cleared on result. Two
     * separate slots (one per launcher) avoid cross-talk if a future flow
     * pre-arms one before the other returns.
     */
    private var pendingSelectImageCallback: ((Uri?) -> Unit)? = null
    private var pendingUploadArchiveCallback: ((Uri?) -> Unit)? = null

    /**
     * Image-picker launcher used by [GallerySearchBarHelper.onSelectImage].
     * Registered as a property so registration completes before the Fragment
     * reaches STARTED — required by the AndroidX activity-result API.
     */
    private val selectImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = pendingSelectImageCallback
        pendingSelectImageCallback = null
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        cb?.invoke(uri)
    }

    /**
     * Archive-picker launcher used by [GalleryUploadHelper.showUploadFilePicker].
     */
    private val uploadArchiveLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = pendingUploadArchiveCallback
        pendingUploadArchiveCallback = null
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        cb?.invoke(uri)
    }

    /** Bridge for [GallerySearchBarHelper] — see its `doPickImage` parameter. */
    internal fun launchPickImage(intent: Intent, onPicked: (Uri?) -> Unit) {
        pendingSelectImageCallback = onPicked
        selectImageLauncher.launch(intent)
    }

    /** Bridge for [GalleryUploadHelper.Callback.pickArchive]. */
    internal fun launchPickArchive(intent: Intent, onPicked: (Uri?) -> Unit) {
        pendingUploadArchiveCallback = onPicked
        uploadArchiveLauncher.launch(intent)
    }

    private val mOnScrollListener: RecyclerView.OnScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {}

        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy >= mHideActionFabSlop) {
                stateHelper?.hideActionFab()
            } else if (dy <= -mHideActionFabSlop / 2) {
                stateHelper?.showActionFab()
            }
        }
    }

    override fun getNavCheckedItem(): Int {
        return mNavCheckedId
    }

    private fun handleArgs(args: Bundle?) {
        val urlBuilder = mUrlBuilder
        if (args == null || urlBuilder == null) {
            return
        }

        val action = args.getString(KEY_ACTION)
        when {
            ACTION_HOMEPAGE == action -> {
                urlBuilder.reset()
            }
            ACTION_SUBSCRIPTION == action -> {
                urlBuilder.reset()
                urlBuilder.mode = ListUrlBuilder.MODE_SUBSCRIPTION
            }
            ACTION_WHATS_HOT == action -> {
                urlBuilder.reset()
                urlBuilder.mode = ListUrlBuilder.MODE_WHATS_HOT
            }
            ACTION_LIST_URL_BUILDER == action -> {
                val builder: ListUrlBuilder? = args.getParcelable(KEY_LIST_URL_BUILDER)
                if (builder != null) {
                    urlBuilder.set(builder)
                }
            }
            ACTION_TOP_LIST == action -> {
                urlBuilder.reset()
                urlBuilder.mode = ListUrlBuilder.MODE_NORMAL
            }
        }
    }

    override fun onNewArguments(args: Bundle) {
        handleArgs(args)
        onUpdateUrlBuilder()
        mHelper?.refresh()
        stateHelper?.setState(GalleryStateHelper.STATE_NORMAL)
        searchBarMover?.showSearchBar()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val context = ehContext
        AssertUtils.assertNotNull(context)
        viewModel = ViewModelProvider(requireActivity())[GalleryListViewModel::class.java]
        downloadManager = viewModel.downloadManager

        // Start observing download + favourite changes in the ViewModel.
        // The ViewModel owns the listeners and unregisters them in onCleared().
        viewModel.startObservingDownloads()
        viewModel.startObservingFavourites()

        // Observe download events to refresh adapter items
        collectFlow(this, viewModel.downloadEvent) { event ->
            when (event) {
                is GalleryListViewModel.DownloadEvent.ItemUpdated -> {
                    val adapter = adapter ?: return@collectFlow
                    val count = adapter.itemCount
                    for (i in 0 until count) {
                        val archive = adapter.getDataAt(i)
                        if (archive != null && archive.arcid == event.arcid) {
                            adapter.notifyItemChanged(i)
                            break
                        }
                    }
                }
                is GalleryListViewModel.DownloadEvent.BulkChanged -> {
                    adapter?.notifyItemRangeChanged(0, adapter?.itemCount ?: 0)
                }
            }
        }

        // Observe favourite status changes to refresh all visible items
        collectFlow(this, viewModel.favouriteStatusChanged) {
            adapter?.notifyItemRangeChanged(0, adapter?.itemCount ?: 0)
        }

        // Observe archive upload progress/result to drive the progress dialog + tips.
        collectFlow(this, viewModel.uploadState) { state ->
            renderUploadState(state)
        }

        // Server-side archive deletion: drop the row from this list. If the scene is
        // currently visible, remove inline so the user sees the fade + reflow animation.
        // If hidden (covered by the detail page), defer to onResume so the animation
        // plays after the back-stack pop instead of being burned off-screen.
        collectFlow(this, AppEventBus.archiveDeletedEvent) { event ->
            if (isResumed) {
                removeArchiveLocally(event.arcid)
            } else {
                pendingDeletedArcIds.add(event.arcid)
            }
        }

        if (savedInstanceState == null) {
            onInit()
        } else {
            onRestore(savedInstanceState)
        }
    }

    fun onInit() {
        mUrlBuilder = ListUrlBuilder()
        handleArgs(arguments)
    }

    @Suppress("DEPRECATION")
    private fun onRestore(savedInstanceState: Bundle) {
        mHasFirstRefresh = savedInstanceState.getBoolean(KEY_HAS_FIRST_REFRESH)
        mUrlBuilder = savedInstanceState.getParcelable(KEY_LIST_URL_BUILDER)
        mRestoredState = savedInstanceState.getInt(KEY_STATE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        val hasFirstRefresh = if (mHelper != null && 1 == mHelper?.shownViewIndex) {
            false
        } else {
            mHasFirstRefresh
        }
        outState.putBoolean(KEY_HAS_FIRST_REFRESH, hasFirstRefresh)
        outState.putParcelable(KEY_LIST_URL_BUILDER, mUrlBuilder)
        outState.putInt(KEY_STATE, stateHelper?.state ?: GalleryStateHelper.STATE_NORMAL)
    }

    override fun onDestroy() {
        super.onDestroy()
        mUrlBuilder = null
        // DownloadInfoListener and FavouriteStatusRouter.Listener are managed by the
        // ViewModel and unregistered in its onCleared() — no cleanup needed here.
    }

    override fun onSceneResult(requestCode: Int, resultCode: Int, data: android.os.Bundle?) {
        if (requestCode == GalleryItemActionHelper.REQUEST_CODE_GALLERY_DETAIL
            && resultCode == RESULT_OK && data != null
        ) {
            val arcid = data.getString(GalleryDetailScene.KEY_ARCID)
            val rating = data.getFloat(GalleryDetailScene.KEY_RATING_RESULT, Float.NaN)
            if (arcid != null && !rating.isNaN()) {
                val list = mHelper?.getData() ?: return
                for (i in list.indices) {
                    if (list[i].arcid == arcid) {
                        list[i] = list[i].copy(rating = rating)
                        adapter?.notifyItemChanged(i)
                        break
                    }
                }
            }
        }
        super.onSceneResult(requestCode, resultCode, data)
    }

    fun onUpdateUrlBuilder() {
        val builder = mUrlBuilder
        val resources = resources2
        if (resources == null || builder == null || !::searchLayout.isInitialized) {
            return
        }

        var keyword = builder.keyword
        val category = builder.category

        if (!keyword.isNullOrEmpty() && ::searchBar.isInitialized) {
            if (builder.mode == ListUrlBuilder.MODE_TAG) {
                keyword = GallerySearchHelper.wrapTagKeyword(keyword)
            }
            searchBar.setText(keyword)
            searchBar.cursorToEnd()
        }

        var title = GallerySearchHelper.getSuitableTitleForUrlBuilder(resources, builder, true)
        if (title == null) {
            title = resources.getString(R.string.search)
        }
        if (::searchBar.isInitialized) searchBar.setTitle(title)

        val checkedItemId = if (ListUrlBuilder.MODE_NORMAL == builder.mode &&
            LRRUtils.NONE == category &&
            TextUtils.isEmpty(keyword)
        ) {
            R.id.nav_homepage
        } else {
            0
        }
        setNavCheckedItem(checkedItemId)
        mNavCheckedId = checkedItemId
    }

    override fun onCreateView2(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.scene_gallery_list, container, false)

        val context = ehContext!!
        AssertUtils.assertNotNull(context)
        val resources = context.resources

        mHideActionFabSlop = ViewConfiguration.get(context).scaledTouchSlop

        val mainLayout = ViewUtils.`$$`(view, R.id.main_layout)
        val contentLayout = ViewUtils.`$$`(mainLayout, R.id.content_layout) as ContentLayout
        recyclerView = contentLayout.recyclerView
        val fastScroller = contentLayout.fastScroller
        val refreshLayout: RefreshLayout = contentLayout.refreshLayout
        searchLayout = ViewUtils.`$$`(mainLayout, R.id.search_layout) as SearchLayout
        searchBar = ViewUtils.`$$`(mainLayout, R.id.search_bar) as SearchBar
        fabLayout = ViewUtils.`$$`(mainLayout, R.id.fab_layout) as FabLayout
        floatingActionButton = ViewUtils.`$$`(fabLayout, R.id.tag_filter) as FloatingActionButton

        searchFab = ViewUtils.`$$`(mainLayout, R.id.search_fab)

        val paddingTopSB = resources.getDimensionPixelOffset(R.dimen.gallery_padding_top_search_bar)
        val paddingBottomFab = resources.getDimensionPixelOffset(R.dimen.gallery_padding_bottom_fab)

        viewTransition = ViewTransition(contentLayout, searchLayout)

        // Initialize helpers
        initHelpers(context)

        filterHelper?.updateFilterIcon(filterHelper?.filterTagList?.size ?: 0)

        contentLayout.setHelper(mHelper)
        contentLayout.fastScroller.setOnDragHandlerListener(searchBarHelper)

        mAdapterImpl = GalleryListAdapter(
            inflater, resources,
            recyclerView, AppearanceSettings.getListMode()
        )
        mAdapterImpl?.setThumbItemClickListener(object : GalleryAdapterNew.OnThumbItemClickListener {
            override fun onThumbItemClick(position: Int, view: View, archive: Archive?) {
                tagChipHelper?.onThumbItemClick(position, view, archive)
            }
        })
        recyclerView.selector = Ripple.generateRippleDrawable(
            context, !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme),
            Color.TRANSPARENT.toDrawable()
        )
        recyclerView.setDrawSelectorOnTop(true)
        recyclerView.setClipToPadding(false)
        recyclerView.setOnItemClickListener(this)
        recyclerView.setOnItemLongClickListener(this)
        recyclerView.addOnScrollListener(mOnScrollListener)
        fastScroller.setPadding(
            fastScroller.paddingLeft, fastScroller.paddingTop + paddingTopSB,
            fastScroller.paddingRight, fastScroller.paddingBottom
        )

        refreshLayout.setHeaderTranslationY(paddingTopSB.toFloat())

        leftDrawable = DrawerArrowDrawable(context, AttrResources.getAttrColor(context, R.attr.drawableColorPrimary))
        rightDrawable = AddDeleteDrawable(context, AttrResources.getAttrColor(context, R.attr.drawableColorPrimary))
        searchBar.setLeftDrawable(leftDrawable)
        searchBar.setRightDrawable(rightDrawable)
        searchBar.setHelper(searchBarHelper)
        searchBar.setOnStateChangeListener(searchBarHelper)
        GallerySearchHelper.setSearchBarHint(context, searchBar)
        mSearchHelper?.let { searchBar.setSuggestionProvider(it.createSuggestionProvider()) }

        searchLayout.setHelper(searchBarHelper)
        searchLayout.setPadding(
            searchLayout.paddingLeft, searchLayout.paddingTop + paddingTopSB,
            searchLayout.paddingRight, searchLayout.paddingBottom + paddingBottomFab
        )

        fabLayout.setAutoCancel(true)
        fabLayout.isExpanded = false
        fabLayout.setHidePrimaryFab(false)
        fabLayout.setOnClickFabListener(mFabHelper)
        fabLayout.setOnExpandListener(mFabHelper)
        addAboveSnackView(fabLayout)

        actionFabDrawable = AddDeleteDrawable(context, resources.getColor(R.color.primary_drawable_dark, null))
        fabLayout.primaryFab.setImageDrawable(actionFabDrawable)

        searchFab.setOnClickListener(this)

        if (LRRAuthManager.getServerUrl() == null) {
            val fabUpload: FloatingActionButton? = mainLayout.findViewById(R.id.fab_upload)
            val fabUrlDownload: FloatingActionButton? = mainLayout.findViewById(R.id.fab_url_download)
            if (fabUpload != null) fabLayout.removeView(fabUpload)
            if (fabUrlDownload != null) fabLayout.removeView(fabUrlDownload)
        }

        searchBarMover = SearchBarMover(searchBarHelper, searchBar, recyclerView, searchLayout)

        onUpdateUrlBuilder()

        // Restore state
        stateHelper?.setState(GalleryStateHelper.STATE_NORMAL, false)
        stateHelper?.setState(mRestoredState, false)
        mRestoredState = GalleryStateHelper.STATE_NORMAL

        if (!mHasFirstRefresh) {
            mHasFirstRefresh = true
            mHelper?.firstRefresh()
        }

        guideQuickSearch()

        return view
    }

    private fun initHelpers(context: Context) {
        val result = GalleryListHelperFactory.create(this, context)
        filterHelper = result.filterHelper
        stateHelper = result.stateHelper
        itemActionHelper = result.itemActionHelper
        tagChipHelper = result.tagChipHelper
        mHelper = result.dataHelper
        mSearchHelper = result.searchHelper
        listSearchHelper = result.listSearchHelper
        uploadHelper = result.uploadHelper
        searchBarHelper = result.searchBarHelper
        mFabHelper = result.fabHelper
        mDrawerHelper = result.drawerHelper
    }

    /** Entry point for [GalleryUploadHelper] — hand the prepared request to the ViewModel. */
    internal fun startArchiveUpload(request: GalleryListViewModel.UploadRequest) {
        viewModel.uploadArchive(request)
    }

    /**
     * Render the upload [state] into the determinate progress dialog and one-shot
     * tips. Terminal states reset the ViewModel back to Idle so they are not
     * replayed if the Scene re-subscribes (e.g. after a configuration change).
     */
    private fun renderUploadState(state: GalleryListViewModel.UploadUiState) {
        when (state) {
            GalleryListViewModel.UploadUiState.Idle ->
                dismissUploadProgressDialog()

            GalleryListViewModel.UploadUiState.Preparing -> {
                showUploadProgressDialog()
                uploadProgressTitle?.setText(R.string.lrr_upload_preparing)
                uploadProgressBar?.progress = 0
                uploadProgressPercent?.text = ""
            }

            is GalleryListViewModel.UploadUiState.Uploading -> {
                showUploadProgressDialog()
                uploadProgressTitle?.setText(R.string.lrr_upload_uploading)
                uploadProgressBar?.progress = state.percent
                uploadProgressPercent?.text = "${state.percent}%"
            }

            is GalleryListViewModel.UploadUiState.DuplicateSkipped -> {
                dismissUploadProgressDialog()
                showTip(R.string.lrr_upload_duplicate, BaseScene.LENGTH_LONG)
                viewModel.resetUploadState()
            }

            is GalleryListViewModel.UploadUiState.Success -> {
                dismissUploadProgressDialog()
                showTip(R.string.lrr_upload_success, BaseScene.LENGTH_LONG)
                mHelper?.refresh()
                viewModel.resetUploadState()
            }

            is GalleryListViewModel.UploadUiState.Failed -> {
                dismissUploadProgressDialog()
                val context = ehContext
                val message = if (context != null) {
                    friendlyError(context, state.error)
                } else {
                    state.error.message ?: ""
                }
                showTip(getString(R.string.lrr_upload_failed, message), BaseScene.LENGTH_LONG)
                viewModel.resetUploadState()
            }
        }
    }

    private fun showUploadProgressDialog() {
        if (uploadProgressDialog?.isShowing == true) return
        val activity = activity2 ?: return
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_upload_progress, null, false)
        uploadProgressTitle = view.findViewById(R.id.upload_progress_title)
        uploadProgressBar = view.findViewById(R.id.upload_progress_bar)
        uploadProgressPercent = view.findViewById(R.id.upload_progress_percent)
        uploadProgressDialog = AlertDialog.Builder(activity)
            .setView(view)
            .setNegativeButton(R.string.cancel) { d, _ -> d.cancel() }
            .setCancelable(true)
            .create()
            .also { dialog ->
                dialog.setOnCancelListener { viewModel.cancelUpload() }
                dialog.show()
            }
    }

    private fun dismissUploadProgressDialog() {
        uploadProgressDialog?.dismiss()
        uploadProgressDialog = null
        uploadProgressBar = null
        uploadProgressTitle = null
        uploadProgressPercent = null
    }

    private fun guideQuickSearch() {
        val activity = activity2
        if (activity == null || !GuideSettings.getGuideQuickSearch()) {
            return
        }

        @Suppress("DEPRECATION")
        val display = activity.windowManager.defaultDisplay
        val point = Point()
        display.getSize(point)

        mShowcaseView = ShowcaseView.Builder(activity)
            .withMaterialShowcase()
            .setStyle(R.style.Guide)
            .setTarget(PointTarget(point.x, point.y / 3))
            .blockAllTouches()
            .setContentTitle(R.string.guide_quick_search_title)
            .setContentText(R.string.guide_quick_search_text)
            .replaceEndButton(R.layout.button_guide)
            .setShowcaseEventListener(object : SimpleShowcaseEventListener() {
                @SuppressLint("RtlHardcoded")
                override fun onShowcaseViewDidHide(showcaseView: ShowcaseView) {
                    mShowcaseView = null
                    ViewUtils.removeFromParent(showcaseView)
                    GuideSettings.putGuideQuickSearch(false)
                    openDrawer(Gravity.RIGHT)
                }
            }).build()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        if (mShowcaseView != null) {
            ViewUtils.removeFromParent(mShowcaseView)
            mShowcaseView = null
        }
        searchBarMover?.cancelAnimation()
        searchBarMover = null
        val helper = mHelper
        if (helper != null) {
            if (1 == helper.shownViewIndex) {
                mHasFirstRefresh = false
            }
        }
        if (::recyclerView.isInitialized) {
            recyclerView.stopScroll()
        }
        if (::fabLayout.isInitialized) {
            removeAboveSnackView(fabLayout)
        }

        dismissUploadProgressDialog()

        mAdapterImpl = null
        viewTransition = null
        leftDrawable = null
        rightDrawable = null
        actionFabDrawable = null
        uploadHelper = null
        listSearchHelper = null
        mFabHelper = null
        searchBarHelper = null
    }

    // Delegation methods for BookmarksDraw (which references scene directly)

    fun showQuickSearchTipDialog(
        list: List<QuickSearch>,
        adapter: ArrayAdapter<QuickSearch>, listView: ListView, tip: TextView
    ) {
        mDrawerHelper?.showQuickSearchTipDialog(list, adapter, listView, tip)
    }

    fun showAddQuickSearchDialog(
        list: List<QuickSearch>,
        adapter: ArrayAdapter<QuickSearch>, listView: ListView, tip: TextView
    ) {
        mDrawerHelper?.showAddQuickSearchDialog(list, adapter, listView, tip)
    }

    // Expose drawPager for BookmarksDraw
    val drawPager get() = mDrawerHelper?.drawPager

    fun setState(state: Int) {
        stateHelper?.setState(state)
    }

    @SuppressLint("RtlHardcoded", "NonConstantResourceId")
    override fun onCreateDrawerView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return mDrawerHelper?.onCreateDrawerView(inflater, container, savedInstanceState)
    }

    private fun checkDoubleClickExit(): Boolean {
        if (stackIndex != 0) {
            return false
        }
        val time = System.currentTimeMillis()
        return if (time - mPressBackTime > BACK_PRESSED_INTERVAL) {
            mPressBackTime = time
            showTip(R.string.press_twice_exit, LENGTH_SHORT)
            true
        } else {
            false
        }
    }

    override fun onResume() {
        super.onResume()
        adapter?.let {
            it.setType(AppearanceSettings.getListMode())
            it.refreshColumnSize()
        }
        mDrawerHelper?.onResume()

        // Drain deletions that arrived while we were hidden, deferred one frame so
        // the RecyclerView has laid out before the remove animation kicks off.
        if (pendingDeletedArcIds.isNotEmpty() && ::recyclerView.isInitialized) {
            val drained = pendingDeletedArcIds.toList()
            pendingDeletedArcIds.clear()
            recyclerView.post {
                drained.forEach { removeArchiveLocally(it) }
            }
        }

        // Auto-refresh when active server config changed (edit/switch/add)
        val currentVersion = LRRAuthManager.serverConfigVersion
        if (currentVersion != mLastServerConfigVersion) {
            mLastServerConfigVersion = currentVersion
            mHelper?.refresh()
        }
    }

    override fun onBackPressed() {
        tagChipHelper?.dismissPopup()
        if (mShowcaseView != null) {
            return
        }

        if (::fabLayout.isInitialized && fabLayout.isExpanded) {
            fabLayout.isExpanded = false
            return
        }

        val filterHelper = filterHelper
        val urlBuilder = mUrlBuilder
        if (filterHelper != null && urlBuilder != null && filterHelper.filterOpen && filterHelper.removeLastFilterTag()) {
            urlBuilder.set(
                filterHelper.listToString(filterHelper.filterTagList),
                ListUrlBuilder.MODE_FILTER
            )
            filterHelper.updateFilterIcon(filterHelper.filterTagList.size)

            urlBuilder.pageIndex = 0
            onUpdateUrlBuilder()
            mHelper?.refresh()
            stateHelper?.setState(GalleryStateHelper.STATE_NORMAL)
            return
        }

        val handle = listSearchHelper?.handleSearchBackPress() ?: false
        if (!handle && !checkDoubleClickExit()) {
            finish()
        }
    }

    override fun onItemClick(parent: EasyRecyclerView, view: View, position: Int, id: Long): Boolean =
        itemActionHelper?.onItemClick(view, mHelper?.getDataAtEx(position)) ?: false

    override fun onItemLongClick(parent: EasyRecyclerView, view: View, position: Int, id: Long): Boolean =
        itemActionHelper?.onItemLongClick(mHelper?.getDataAtEx(position), view) ?: false

    override fun onClick(v: View) {
        listSearchHelper?.onSearchFabClick()
    }

    // SearchBar, SearchLayout, SearchBarMover, FastScroller callbacks are
    // handled by GallerySearchBarHelper (registered in initHelpers)

    // Inner adapter — too small to extract

    private fun removeArchiveLocally(arcid: String) {
        val helper = mHelper ?: return
        for (i in 0 until helper.size()) {
            if (helper.getDataAtEx(i)?.arcid == arcid) {
                helper.removeAt(i)
                return
            }
        }
    }

    private inner class GalleryListAdapter(
        inflater: LayoutInflater,
        resources: Resources, recyclerView: RecyclerView, type: Int
    ) : GalleryAdapterNew(inflater, resources, recyclerView, type, true) {

        override fun getItemCount(): Int {
            return mHelper?.size() ?: 0
        }

        override fun getDataAt(position: Int): Archive? {
            return mHelper?.getDataAtEx(position)
        }
    }

    companion object {
        private const val TAG = "GalleryListScene"

        private const val BACK_PRESSED_INTERVAL = 2000

        const val KEY_ACTION = "action"
        const val ACTION_HOMEPAGE = "action_homepage"
        const val ACTION_SUBSCRIPTION = "action_subscription"
        const val ACTION_WHATS_HOT = "action_whats_hot"
        const val ACTION_TOP_LIST = "action_top_list"
        const val ACTION_LIST_URL_BUILDER = "action_list_url_builder"

        const val KEY_LIST_URL_BUILDER = "list_url_builder"
        const val KEY_HAS_FIRST_REFRESH = "has_first_refresh"
        const val KEY_STATE = "state"

        // Keep for backward compat with references
        const val STATE_NORMAL = GalleryStateHelper.STATE_NORMAL
        const val STATE_SIMPLE_SEARCH = GalleryStateHelper.STATE_SIMPLE_SEARCH
        const val STATE_SEARCH = GalleryStateHelper.STATE_SEARCH
        const val STATE_SEARCH_SHOW_LIST = GalleryStateHelper.STATE_SEARCH_SHOW_LIST

        @JvmStatic
        fun startScene(scene: com.hippo.scene.SceneFragment, lub: ListUrlBuilder) {
            scene.startScene(getStartAnnouncer(lub))
        }

        @JvmStatic
        fun getStartAnnouncer(lub: ListUrlBuilder): Announcer {
            val args = Bundle()
            args.putString(KEY_ACTION, ACTION_LIST_URL_BUILDER)
            args.putParcelable(KEY_LIST_URL_BUILDER, lub)
            return Announcer(GalleryListScene::class.java).setArgs(args)
        }
    }
}
