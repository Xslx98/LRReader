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

package com.lanraragi.reader.ui.scene.gallery.list

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Point
import androidx.core.graphics.drawable.toDrawable
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
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.hippo.android.resource.AttrResources
import com.lanraragi.framework.drawable.AddDeleteDrawable
import com.lanraragi.framework.drawable.DrawerArrowDrawable
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.lanraragi.reader.R
import com.lanraragi.reader.client.LRRUtils

import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.client.data.ListUrlBuilder
import com.lanraragi.reader.dao.QuickSearch
import com.lanraragi.reader.download.DownloadManager
import com.lanraragi.reader.settings.AppearanceSettings
import com.lanraragi.reader.settings.GuideSettings
import com.lanraragi.reader.ui.scene.BaseScene
import com.lanraragi.reader.ui.scene.ListMultiSelectHelper
import com.lanraragi.reader.ui.scene.gallery.detail.GalleryDetailScene
import com.lanraragi.reader.widget.SearchBar
import com.lanraragi.reader.widget.SearchLayout
import com.lanraragi.reader.widget.SkeletonView
import com.lanraragi.framework.lib.yorozuya.AssertUtils
import com.lanraragi.framework.lib.yorozuya.ViewUtils
import com.hippo.refreshlayout.RefreshLayout
import com.lanraragi.reader.ui.widget.Ripples
import com.lanraragi.framework.scene.Announcer
import com.lanraragi.reader.util.collectFlow
import com.lanraragi.framework.view.ViewTransition
import com.lanraragi.framework.widget.ContentLayout
import com.lanraragi.framework.widget.FabLayout
import com.lanraragi.framework.widget.SearchBarMover
import com.lanraragi.reader.client.api.LRRAuthManager

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

    /** True once onCreateView2 has created the FAB menu. */
    internal val isFabLayoutCreated: Boolean
        get() = ::fabLayout.isInitialized
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

    /** The query this scene's rows came from (not the shared ViewModel's; C01). */
    internal val shownSearch = ShownSearchState()
    private var mLastServerConfigVersion = LRRAuthManager.serverConfigVersion
    private var mNavCheckedId = 0
    private var mRestoredState = GalleryStateHelper.STATE_NORMAL

    // Stable per-instance token stamped on batches this scene starts. The
    // ViewModel is activity-scoped and shared, so its result is broadcast to
    // every stacked list instance's collector; matching on this token keeps
    // handling/buffering to the one scene that ran the batch. Scene-level (not
    // in the per-view Callback object) so it survives view recreation, matching
    // the fragment-scoped collector.
    internal val batchOwnerToken = Any()

    internal lateinit var downloadManager: DownloadManager
        private set

    internal lateinit var viewModel: GalleryListViewModel
        private set

    /*---------------
     Extracted helpers — internal visibility for GalleryListHelperFactory
     ---------------*/
    internal var filterHelper: GalleryFilterHelper? = null
    internal var stateHelper: GalleryStateHelper? = null
    internal var tagChipHelper: GalleryTagChipHelper? = null
    internal var itemActionHelper: GalleryItemActionHelper? = null
    internal var multiSelectHelper: ListMultiSelectHelper? = null
    internal var batchOpsHelper: GalleryBatchOpsHelper? = null

    /** Scene root FrameLayout: hosts the merge animation's overlay flyers. */
    internal var rootLayout: ViewGroup? = null

    /** True once onCreateView2 has created the list view. */
    internal val isListViewCreated: Boolean
        get() = ::recyclerView.isInitialized

    internal val tankMerge = GalleryTankMergeHelper(this)
    internal val rowHighlighter = GalleryRowHighlighter(this)
    private var loadingSkeleton: SkeletonView? = null
    private val events = GalleryListEvents(this)
    private val interactions = GalleryListInteractions(this)
    internal var uploadHelper: GalleryUploadHelper? = null
    internal var mSearchHelper: GallerySearchHelper? = null
    internal var mHistoryStore: ProfileSearchHistoryStore? = null
    internal var listSearchHelper: GalleryListSearchHelper? = null
    private var mDrawerHelper: GalleryDrawerHelper? = null
    internal var mFabHelper: GalleryFabHelper? = null
    internal var searchBarHelper: GallerySearchBarHelper? = null

    private val uploadProgress = GalleryUploadProgressPresenter(this)

    /**
     * Archive-picker launcher used by [GalleryUploadHelper.showUploadFilePicker].
     * Registered as a property so registration completes before the Fragment
     * reaches STARTED — required by the AndroidX activity-result API.
     */
    private val uploadArchiveLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Handled directly, not through a stashed lambda: after the Activity
        // is recreated while the picker is open (rotation, process death)
        // the result reaches this new Fragment, and a stashed callback
        // would be gone — the pick silently dropped.
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri != null) uploadHelper?.handleUploadResult(uri)
    }

    /** Bridge for [GalleryUploadHelper.Callback.pickArchive]. */
    internal fun launchPickArchive(intent: Intent) {
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

        // Start observing download changes in the ViewModel. The ViewModel owns
        // the listener and unregisters it in onCleared(). (Favourite changes are
        // deliberately NOT observed: list rows render no favourite state, so the
        // old full-range rebind on every favourite toggle updated nothing.)
        viewModel.startObservingDownloads()

        events.install()

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
        shownSearch.restore(savedInstanceState)
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
        shownSearch.save(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        mUrlBuilder = null
        // The DownloadInfoListener is managed by the ViewModel and unregistered
        // in its onCleared() — no cleanup needed here.
    }

    fun onUpdateUrlBuilder() {
        // The query is changing (search / tag / bookmark / filter / new args):
        // the list is about to reload and adapter positions become meaningless,
        // so any in-flight multi-select must not survive it.
        multiSelectHelper?.exit()
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
            builder.categoryId == null &&
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
        rootLayout = mainLayout as ViewGroup
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

        // First load shows a skeleton of the list instead of the spinner.
        loadingSkeleton = SkeletonView(context).also {
            it.setPadding(0, paddingTopSB, 0, 0)
            contentLayout.setLoadingView(it)
        }
        shapeLoadingSkeleton()
        contentLayout.setHelper(mHelper)
        contentLayout.fastScroller.setOnDragHandlerListener(searchBarHelper)

        mAdapterImpl = GalleryListAdapter(
            inflater, resources,
            recyclerView, AppearanceSettings.getListMode()
        )
        recyclerView.selector = Ripples.rippleDrawable(
            !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme),
            Color.TRANSPARENT.toDrawable()
        )
        recyclerView.setDrawSelectorOnTop(true)
        recyclerView.setClipToPadding(false)
        recyclerView.setOnItemClickListener(this)
        recyclerView.setOnItemLongClickListener(this)
        mAdapterImpl?.let { GalleryListSelection.bind(this, it, mainLayout) }
        recyclerView.addOnScrollListener(mOnScrollListener)
        fastScroller.setPadding(
            fastScroller.paddingLeft, fastScroller.paddingTop + paddingTopSB,
            fastScroller.paddingRight, fastScroller.paddingBottom
        )

        refreshLayout.setHeaderTranslationY(paddingTopSB.toFloat())

        GalleryListChrome.bind(this, context, mainLayout, paddingTopSB, paddingBottomFab)

        onUpdateUrlBuilder()

        // Restore state
        stateHelper?.setState(GalleryStateHelper.STATE_NORMAL, false)
        stateHelper?.setState(mRestoredState, false)
        mRestoredState = GalleryStateHelper.STATE_NORMAL

        if (!mHasFirstRefresh) {
            mHasFirstRefresh = true
            mHelper?.firstRefresh()
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Batch progress drives the bottom action bar. View-scoped (not
        // fragment-scoped like the onCreate collectors) so a re-created view
        // does not stack duplicate collectors targeting a dead helper.
        // batchProgress is a replayed StateFlow, so a fresh view re-renders the
        // current progress on re-attach. batchResultEvent (replay=0) is instead
        // collected fragment-scoped in onCreate + buffered, so a batch finishing
        // while this list is detached under a pushed scene is not lost.
        collectFlow(viewLifecycleOwner, viewModel.batchProgress) {
            batchOpsHelper?.onBatchProgress(it)
        }
        // Upload progress/result rendering is view-scoped: the ViewModel is
        // activity-scoped and several GalleryListScene instances can sit in the
        // back stack — fragment-scoped collection rendered one progress dialog
        // per live instance (stacked dialogs, double success tip) and poked
        // mHelper on detached scenes. StateFlow replay restores the dialog on
        // re-attach mid-upload and defers a Success that landed while this
        // scene was covered to return time.
        collectFlow(viewLifecycleOwner, viewModel.uploadState) { state ->
            uploadProgress.render(state)
        }
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

    override fun onPause() {
        super.onPause()
        // A merge mid-flight would be burned off-screen: settle the list now.
        cancelTankMerge()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        cancelTankMerge()
        rootLayout = null
        loadingSkeleton = null
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

        uploadProgress.dismiss()

        mAdapterImpl = null
        viewTransition = null
        leftDrawable = null
        rightDrawable = null
        actionFabDrawable = null
        multiSelectHelper = null
        batchOpsHelper = null
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

    override fun onResume() {
        super.onResume()
        // Re-sync the history snapshot: the active profile may have changed
        // while this scene was backgrounded.
        mHistoryStore?.refresh()
        tankMerge.rebindTankRowsIfCoverStampMoved()
        adapter?.let {
            it.setType(AppearanceSettings.getListMode())
            it.refreshColumnSize()
        }
        shapeLoadingSkeleton()
        mDrawerHelper?.onResume()

        events.drainOnResume()
        rowHighlighter.flushAfterReturn()

        // Auto-refresh when active server config changed (edit/switch/add)
        val currentVersion = LRRAuthManager.serverConfigVersion
        if (currentVersion != mLastServerConfigVersion) {
            mLastServerConfigVersion = currentVersion
            // The whole result set is replaced by another server's data — a
            // stale selection must not carry across profiles.
            multiSelectHelper?.exit()
            mHelper?.refresh()
        }
    }

    override fun onBackPressed() {
        interactions.onBackPressed()
    }

    /** The first-load skeleton takes the shape of the current list mode. */
    private fun shapeLoadingSkeleton() {
        val skeleton = loadingSkeleton ?: return
        if (AppearanceSettings.getListMode() == GalleryAdapterNew.TYPE_GRID) {
            skeleton.shape = SkeletonView.Shape.GRID
            skeleton.columnWidth = resources.getDimensionPixelOffset(AppearanceSettings.getThumbSizeResId())
        } else {
            skeleton.shape = SkeletonView.Shape.LIST
        }
    }

    override fun hasInSceneBack(): Boolean = interactions.hasInSceneBack()

    override fun onItemClick(parent: EasyRecyclerView, view: View, position: Int, id: Long): Boolean =
        interactions.onItemClick(view, position)

    override fun onItemLongClick(parent: EasyRecyclerView, view: View, position: Int, id: Long): Boolean =
        onListItemLongClick(position)

    /** Long-press enters multi-select — except on tank pseudo-entries. */
    internal fun onListItemLongClick(position: Int): Boolean = interactions.onItemLongClick(position)

    override fun onClick(v: View) {
        listSearchHelper?.onSearchFabClick()
    }

    // SearchBar, SearchLayout, SearchBarMover, FastScroller callbacks are
    // handled by GallerySearchBarHelper (registered in initHelpers)

    // Inner adapter — too small to extract

    /** Settles an in-flight merge (end state applied, feedback delivered). */
    internal fun cancelTankMerge() {
        tankMerge.cancelTankMerge()
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
        fun startScene(scene: com.lanraragi.framework.scene.SceneFragment, lub: ListUrlBuilder) {
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
