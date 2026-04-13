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
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextUtils
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
import com.github.amlcurran.showcaseview.ShowcaseView
import com.github.amlcurran.showcaseview.SimpleShowcaseEventListener
import com.github.amlcurran.showcaseview.targets.PointTarget
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.hippo.android.resource.AttrResources
import com.hippo.drawable.AddDeleteDrawable
import com.hippo.drawable.DrawerArrowDrawable
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryInfoUi
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.dao.QuickSearch
import com.hippo.ehviewer.download.DownloadManager
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
    internal var recyclerView: EasyRecyclerView? = null
    internal var searchLayout: SearchLayout? = null
    internal var searchBar: SearchBar? = null
    internal var searchFab: View? = null
    internal var fabLayout: FabLayout? = null
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

    private var mShowcaseView: ShowcaseView? = null
    internal lateinit var downloadManager: DownloadManager
        private set

    private lateinit var viewModel: GalleryListViewModel

    /*---------------
     Extracted helpers — internal visibility for GalleryListHelperFactory
     ---------------*/
    internal var filterHelper: GalleryFilterHelper? = null
    internal var goToHelper: GalleryGoToHelper? = null
    internal var stateHelper: GalleryStateHelper? = null
    internal var tagChipHelper: GalleryTagChipHelper? = null
    internal var itemActionHelper: GalleryItemActionHelper? = null
    internal var uploadHelper: GalleryUploadHelper? = null
    private var mSearchHelper: GallerySearchHelper? = null
    internal var listSearchHelper: GalleryListSearchHelper? = null
    private var mDrawerHelper: GalleryDrawerHelper? = null
    private var mFabHelper: GalleryFabHelper? = null
    private var searchBarHelper: GallerySearchBarHelper? = null

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
        if (args == null || mUrlBuilder == null) {
            return
        }

        val action = args.getString(KEY_ACTION)
        when {
            ACTION_HOMEPAGE == action -> {
                mUrlBuilder!!.reset()
            }
            ACTION_SUBSCRIPTION == action -> {
                mUrlBuilder!!.reset()
                mUrlBuilder!!.mode = ListUrlBuilder.MODE_SUBSCRIPTION
            }
            ACTION_WHATS_HOT == action -> {
                mUrlBuilder!!.reset()
                mUrlBuilder!!.mode = ListUrlBuilder.MODE_WHATS_HOT
            }
            ACTION_LIST_URL_BUILDER == action -> {
                val builder: ListUrlBuilder? = args.getParcelable(KEY_LIST_URL_BUILDER)
                if (builder != null) {
                    mUrlBuilder!!.set(builder)
                }
            }
            ACTION_TOP_LIST == action -> {
                mUrlBuilder!!.reset()
                mUrlBuilder!!.mode = ListUrlBuilder.MODE_NORMAL
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
                        val gi = adapter.getDataAt(i)
                        if (gi != null && gi.gid == event.gid) {
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

        val hasFirstRefresh = if (mHelper != null && 1 == mHelper!!.shownViewIndex) {
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
            val gid = data.getLong(GalleryDetailScene.KEY_GID, -1)
            val rating = data.getFloat(GalleryDetailScene.KEY_RATING_RESULT, -1f)
            if (gid >= 0 && rating >= 0) {
                val list = mHelper?.getData() ?: return
                for (i in list.indices) {
                    if (list[i].gid == gid) {
                        list[i].rating = rating
                        list[i].rated = true
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
        if (resources == null || builder == null || searchLayout == null) {
            return
        }

        var keyword = builder.keyword
        val category = builder.category

        if (!keyword.isNullOrEmpty() && searchBar != null) {
            if (builder.mode == ListUrlBuilder.MODE_TAG) {
                keyword = GallerySearchHelper.wrapTagKeyword(keyword)
            }
            searchBar!!.setText(keyword)
            searchBar!!.cursorToEnd()
        }

        var title = GallerySearchHelper.getSuitableTitleForUrlBuilder(resources, builder, true)
        if (title == null) {
            title = resources.getString(R.string.search)
        }
        searchBar?.setTitle(title)

        val checkedItemId = if (ListUrlBuilder.MODE_NORMAL == builder.mode &&
            EhUtils.NONE == category &&
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

        filterHelper!!.updateFilterIcon(filterHelper!!.filterTagList.size)

        contentLayout.setHelper(mHelper)
        contentLayout.fastScroller.setOnDragHandlerListener(searchBarHelper)

        mAdapterImpl = GalleryListAdapter(
            inflater, resources,
            recyclerView!!, AppearanceSettings.getListMode()
        )
        mAdapterImpl!!.setThumbItemClickListener(object : GalleryAdapterNew.OnThumbItemClickListener {
            override fun onThumbItemClick(position: Int, view: View, gi: GalleryInfoUi?) {
                tagChipHelper?.onThumbItemClick(position, view, gi)
            }
        })
        recyclerView!!.selector = Ripple.generateRippleDrawable(
            context, !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme),
            ColorDrawable(Color.TRANSPARENT)
        )
        recyclerView!!.setDrawSelectorOnTop(true)
        recyclerView!!.setClipToPadding(false)
        recyclerView!!.setOnItemClickListener(this)
        recyclerView!!.setOnItemLongClickListener(this)
        recyclerView!!.addOnScrollListener(mOnScrollListener)
        fastScroller.setPadding(
            fastScroller.paddingLeft, fastScroller.paddingTop + paddingTopSB,
            fastScroller.paddingRight, fastScroller.paddingBottom
        )

        refreshLayout.setHeaderTranslationY(paddingTopSB.toFloat())

        leftDrawable = DrawerArrowDrawable(context, AttrResources.getAttrColor(context, R.attr.drawableColorPrimary))
        rightDrawable = AddDeleteDrawable(context, AttrResources.getAttrColor(context, R.attr.drawableColorPrimary))
        searchBar!!.setLeftDrawable(leftDrawable)
        searchBar!!.setRightDrawable(rightDrawable)
        searchBar!!.setHelper(searchBarHelper)
        searchBar!!.setOnStateChangeListener(searchBarHelper)
        GallerySearchHelper.setSearchBarHint(context, searchBar!!)
        searchBar!!.setSuggestionProvider(mSearchHelper!!.createSuggestionProvider())

        searchLayout!!.setHelper(searchBarHelper)
        searchLayout!!.setPadding(
            searchLayout!!.paddingLeft, searchLayout!!.paddingTop + paddingTopSB,
            searchLayout!!.paddingRight, searchLayout!!.paddingBottom + paddingBottomFab
        )

        fabLayout!!.setAutoCancel(true)
        fabLayout!!.isExpanded = false
        fabLayout!!.setHidePrimaryFab(false)
        fabLayout!!.setOnClickFabListener(mFabHelper)
        fabLayout!!.setOnExpandListener(mFabHelper)
        addAboveSnackView(fabLayout!!)

        actionFabDrawable = AddDeleteDrawable(context, resources.getColor(R.color.primary_drawable_dark, null))
        fabLayout!!.primaryFab.setImageDrawable(actionFabDrawable)

        searchFab!!.setOnClickListener(this)

        if (LRRAuthManager.getServerUrl() == null) {
            val fabUpload: FloatingActionButton? = mainLayout.findViewById(R.id.fab_upload)
            val fabUrlDownload: FloatingActionButton? = mainLayout.findViewById(R.id.fab_url_download)
            if (fabUpload != null) fabLayout!!.removeView(fabUpload)
            if (fabUrlDownload != null) fabLayout!!.removeView(fabUrlDownload)
        }

        searchBarMover = SearchBarMover(searchBarHelper, searchBar, recyclerView, searchLayout)

        onUpdateUrlBuilder()

        // Restore state
        stateHelper?.setState(GalleryStateHelper.STATE_NORMAL, false)
        stateHelper?.setState(mRestoredState, false)
        mRestoredState = GalleryStateHelper.STATE_NORMAL

        if (!mHasFirstRefresh) {
            mHasFirstRefresh = true
            mHelper!!.firstRefresh()
        }

        guideQuickSearch()

        return view
    }

    private fun initHelpers(context: Context) {
        val result = GalleryListHelperFactory.create(this, context)
        filterHelper = result.filterHelper
        goToHelper = result.goToHelper
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
        if (searchBarMover != null) {
            searchBarMover!!.cancelAnimation()
            searchBarMover = null
        }
        if (mHelper != null) {
            mHelper!!.destroy()
            if (1 == mHelper!!.shownViewIndex) {
                mHasFirstRefresh = false
            }
        }
        if (recyclerView != null) {
            recyclerView!!.stopScroll()
            recyclerView = null
        }
        if (fabLayout != null) {
            removeAboveSnackView(fabLayout!!)
            fabLayout = null
        }

        mAdapterImpl = null
        searchLayout = null
        searchBar = null
        searchFab = null
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
        if (adapter != null) {
            adapter!!.setType(AppearanceSettings.getListMode())
            adapter!!.refreshColumnSize()
        }
        mDrawerHelper?.onResume()

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

        if (fabLayout != null && fabLayout!!.isExpanded) {
            fabLayout!!.isExpanded = false
            return
        }

        val filterHelper = filterHelper
        if (filterHelper != null && filterHelper.filterOpen && filterHelper.removeLastFilterTag()) {
            mUrlBuilder!!.set(
                filterHelper.listToString(filterHelper.filterTagList),
                ListUrlBuilder.MODE_FILTER
            )
            filterHelper.updateFilterIcon(filterHelper.filterTagList.size)

            mUrlBuilder!!.pageIndex = 0
            onUpdateUrlBuilder()
            mHelper!!.refresh()
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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (REQUEST_CODE_SELECT_IMAGE == requestCode) {
            if (Activity.RESULT_OK == resultCode && searchLayout != null && data != null) {
                searchLayout!!.setImageUri(data.data)
            }
        } else if (REQUEST_CODE_UPLOAD_ARCHIVE == requestCode) {
            if (Activity.RESULT_OK == resultCode && data?.data != null) {
                uploadHelper?.handleUploadResult(data.data!!)
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // Inner adapter — too small to extract

    private inner class GalleryListAdapter(
        inflater: LayoutInflater,
        resources: Resources, recyclerView: RecyclerView, type: Int
    ) : GalleryAdapterNew(inflater, resources, recyclerView, type, true) {

        override fun getItemCount(): Int {
            return mHelper?.size() ?: 0
        }

        override fun getDataAt(position: Int): GalleryInfoUi? {
            return mHelper?.getDataAtEx(position)
        }
    }

    companion object {
        private const val TAG = "GalleryListScene"

        private const val BACK_PRESSED_INTERVAL = 2000

        const val REQUEST_CODE_SELECT_IMAGE = 0
        const val REQUEST_CODE_UPLOAD_ARCHIVE = 1

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
