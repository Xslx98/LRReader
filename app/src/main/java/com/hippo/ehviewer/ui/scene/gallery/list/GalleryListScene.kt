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
import androidx.recyclerview.widget.RecyclerView
import com.github.amlcurran.showcaseview.ShowcaseView
import com.github.amlcurran.showcaseview.SimpleShowcaseEventListener
import com.github.amlcurran.showcaseview.targets.PointTarget
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.hippo.android.resource.AttrResources
import com.hippo.drawable.AddDeleteDrawable
import com.hippo.drawable.DrawerArrowDrawable
import com.hippo.drawerlayout.DrawerLayout
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.easyrecyclerview.FastScroller
import androidx.lifecycle.ViewModelProvider
import com.hippo.ehviewer.FavouriteStatusRouter
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryInfoUi
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.exception.EhException
import com.lanraragi.reader.client.api.LRRAuthManager
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.QuickSearch
import com.hippo.ehviewer.download.DownloadInfoListener
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.settings.GuideSettings
import com.hippo.ehviewer.settings.ReadingSettings
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.widget.SearchBar
import com.hippo.ehviewer.widget.SearchLayout
import com.hippo.refreshlayout.RefreshLayout
import com.hippo.ripple.Ripple
import com.hippo.scene.Announcer
import com.hippo.view.ViewTransition
import com.hippo.widget.ContentLayout
import com.hippo.widget.FabLayout
import com.hippo.widget.SearchBarMover
import com.hippo.lib.yorozuya.AssertUtils
import com.hippo.lib.yorozuya.ViewUtils

class GalleryListScene : BaseScene(),
    EasyRecyclerView.OnItemClickListener, EasyRecyclerView.OnItemLongClickListener,
    SearchBar.Helper, SearchBar.OnStateChangeListener, FastScroller.OnDragHandlerListener,
    SearchLayout.Helper, SearchBarMover.Helper, View.OnClickListener, FabLayout.OnClickFabListener,
    FabLayout.OnExpandListener {

    /*---------------
     Whole life cycle
     ---------------*/
    @JvmField
    var mUrlBuilder: ListUrlBuilder? = null

    /*---------------
     View life cycle
     ---------------*/
    private var mRecyclerView: EasyRecyclerView? = null
    private var mSearchLayout: SearchLayout? = null
    private var mSearchBar: SearchBar? = null
    private var mSearchFab: View? = null
    private var mFabLayout: FabLayout? = null
    private var mFloatingActionButton: FloatingActionButton? = null
    private var mViewTransition: ViewTransition? = null
    private var mAdapter: GalleryListAdapter? = null
    @JvmField
    var mHelper: GalleryListDataHelper? = null
    private var mLeftDrawable: DrawerArrowDrawable? = null
    private var mRightDrawable: AddDeleteDrawable? = null
    private var mSearchBarMover: SearchBarMover? = null
    private var mActionFabDrawable: AddDeleteDrawable? = null

    private var mHideActionFabSlop = 0

    private var mHasFirstRefresh = false
    private var mNavCheckedId = 0
    private var mPressBackTime: Long = 0
    private var mRestoredState = GalleryStateHelper.STATE_NORMAL

    private var mShowcaseView: ShowcaseView? = null
    private lateinit var mDownloadManager: DownloadManager
    private var mDownloadInfoListener: DownloadInfoListener? = null
    private lateinit var mFavouriteStatusRouter: FavouriteStatusRouter
    private var mFavouriteStatusRouterListener: FavouriteStatusRouter.Listener? = null

    private lateinit var viewModel: GalleryListViewModel

    /*---------------
     Extracted helpers
     ---------------*/
    private var mFilterHelper: GalleryFilterHelper? = null
    private var mGoToHelper: GalleryGoToHelper? = null
    private var mStateHelper: GalleryStateHelper? = null
    private var mTagChipHelper: GalleryTagChipHelper? = null
    private var mItemActionHelper: GalleryItemActionHelper? = null
    private var mUploadHelper: GalleryUploadHelper? = null
    private var mSearchHelper: GallerySearchHelper? = null
    private var mDrawerHelper: GalleryDrawerHelper? = null

    private val mOnScrollListener: RecyclerView.OnScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {}

        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy >= mHideActionFabSlop) {
                mStateHelper?.hideActionFab()
            } else if (dy <= -mHideActionFabSlop / 2) {
                mStateHelper?.showActionFab()
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
        mStateHelper?.setState(GalleryStateHelper.STATE_NORMAL)
        mSearchBarMover?.showSearchBar()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val context = ehContext
        AssertUtils.assertNotNull(context)
        viewModel = ViewModelProvider(requireActivity())[GalleryListViewModel::class.java]
        mDownloadManager = viewModel.downloadManager
        mFavouriteStatusRouter = viewModel.favouriteStatusRouter

        mDownloadInfoListener = object : DownloadInfoListener {
            override fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
                // Download status icon may appear on matching gallery items — refresh all visible
                mAdapter?.notifyItemRangeChanged(0, mAdapter?.itemCount ?: 0)
            }
            override fun onReplace(newInfo: DownloadInfo, oldInfo: DownloadInfo) {}
            override fun onUpdate(info: DownloadInfo, list: List<DownloadInfo>, mWaitList: List<DownloadInfo>) {}
            override fun onUpdateAll() {}
            override fun onReload() {
                mAdapter?.notifyItemRangeChanged(0, mAdapter?.itemCount ?: 0)
            }
            override fun onChange() {
                mAdapter?.notifyItemRangeChanged(0, mAdapter?.itemCount ?: 0)
            }
            override fun onRenameLabel(from: String, to: String) {}
            override fun onRemove(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
                mAdapter?.notifyItemRangeChanged(0, mAdapter?.itemCount ?: 0)
            }
            override fun onUpdateLabels() {}
        }
        mDownloadInfoListener?.let { mDownloadManager.addDownloadInfoListener(it) }

        mFavouriteStatusRouterListener = FavouriteStatusRouter.Listener { _, _ ->
            mAdapter?.notifyItemRangeChanged(0, mAdapter?.itemCount ?: 0)
        }
        mFavouriteStatusRouter.addListener(mFavouriteStatusRouterListener!!)

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
        outState.putInt(KEY_STATE, mStateHelper?.state ?: GalleryStateHelper.STATE_NORMAL)
    }

    override fun onDestroy() {
        super.onDestroy()
        mUrlBuilder = null
        mDownloadInfoListener?.let { mDownloadManager.removeDownloadInfoListener(it) }
        mFavouriteStatusRouterListener?.let { mFavouriteStatusRouter.removeListener(it) }
    }

    fun onUpdateUrlBuilder() {
        val builder = mUrlBuilder
        val resources = resources2
        if (resources == null || builder == null || mSearchLayout == null) {
            return
        }

        var keyword = builder.keyword
        val category = builder.category

        if (!keyword.isNullOrEmpty() && mSearchBar != null) {
            if (builder.mode == ListUrlBuilder.MODE_TAG) {
                keyword = GallerySearchHelper.wrapTagKeyword(keyword)
            }
            mSearchBar!!.setText(keyword)
            mSearchBar!!.cursorToEnd()
        }

        var title = GallerySearchHelper.getSuitableTitleForUrlBuilder(resources, builder, true)
        if (title == null) {
            title = resources.getString(R.string.search)
        }
        mSearchBar?.setTitle(title)

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
        mRecyclerView = contentLayout.recyclerView
        val fastScroller = contentLayout.fastScroller
        val refreshLayout: RefreshLayout = contentLayout.refreshLayout
        mSearchLayout = ViewUtils.`$$`(mainLayout, R.id.search_layout) as SearchLayout
        mSearchBar = ViewUtils.`$$`(mainLayout, R.id.search_bar) as SearchBar
        mFabLayout = ViewUtils.`$$`(mainLayout, R.id.fab_layout) as FabLayout
        mFloatingActionButton = ViewUtils.`$$`(mFabLayout, R.id.tag_filter) as FloatingActionButton

        mSearchFab = ViewUtils.`$$`(mainLayout, R.id.search_fab)

        val paddingTopSB = resources.getDimensionPixelOffset(R.dimen.gallery_padding_top_search_bar)
        val paddingBottomFab = resources.getDimensionPixelOffset(R.dimen.gallery_padding_bottom_fab)

        mViewTransition = ViewTransition(contentLayout, mSearchLayout)

        // Initialize helpers
        initHelpers(context)

        mFilterHelper!!.updateFilterIcon(mFilterHelper!!.filterTagList.size)

        contentLayout.setHelper(mHelper)
        contentLayout.fastScroller.setOnDragHandlerListener(this)

        mAdapter = GalleryListAdapter(
            inflater, resources,
            mRecyclerView!!, AppearanceSettings.getListMode()
        )
        mAdapter!!.setThumbItemClickListener(object : GalleryAdapterNew.OnThumbItemClickListener {
            override fun onThumbItemClick(position: Int, view: View, gi: GalleryInfoUi?) {
                mTagChipHelper?.onThumbItemClick(position, view, gi)
            }
        })
        mRecyclerView!!.selector = Ripple.generateRippleDrawable(
            context, !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme),
            ColorDrawable(Color.TRANSPARENT)
        )
        mRecyclerView!!.setDrawSelectorOnTop(true)
        mRecyclerView!!.setClipToPadding(false)
        mRecyclerView!!.setOnItemClickListener(this)
        mRecyclerView!!.setOnItemLongClickListener(this)
        mRecyclerView!!.addOnScrollListener(mOnScrollListener)
        fastScroller.setPadding(
            fastScroller.paddingLeft, fastScroller.paddingTop + paddingTopSB,
            fastScroller.paddingRight, fastScroller.paddingBottom
        )

        refreshLayout.setHeaderTranslationY(paddingTopSB.toFloat())

        mLeftDrawable = DrawerArrowDrawable(context, AttrResources.getAttrColor(context, R.attr.drawableColorPrimary))
        mRightDrawable = AddDeleteDrawable(context, AttrResources.getAttrColor(context, R.attr.drawableColorPrimary))
        mSearchBar!!.setLeftDrawable(mLeftDrawable)
        mSearchBar!!.setRightDrawable(mRightDrawable)
        mSearchBar!!.setHelper(this)
        mSearchBar!!.setOnStateChangeListener(this)
        GallerySearchHelper.setSearchBarHint(context, mSearchBar!!)
        mSearchBar!!.setSuggestionProvider(mSearchHelper!!.createSuggestionProvider())

        mSearchLayout!!.setHelper(this)
        mSearchLayout!!.setPadding(
            mSearchLayout!!.paddingLeft, mSearchLayout!!.paddingTop + paddingTopSB,
            mSearchLayout!!.paddingRight, mSearchLayout!!.paddingBottom + paddingBottomFab
        )

        mFabLayout!!.setAutoCancel(true)
        mFabLayout!!.isExpanded = false
        mFabLayout!!.setHidePrimaryFab(false)
        mFabLayout!!.setOnClickFabListener(this)
        mFabLayout!!.setOnExpandListener(this)
        addAboveSnackView(mFabLayout!!)

        mActionFabDrawable = AddDeleteDrawable(context, resources.getColor(R.color.primary_drawable_dark, null))
        mFabLayout!!.primaryFab.setImageDrawable(mActionFabDrawable)

        mSearchFab!!.setOnClickListener(this)

        if (LRRAuthManager.getServerUrl() == null) {
            val fabUpload: FloatingActionButton? = mainLayout.findViewById(R.id.fab_upload)
            val fabUrlDownload: FloatingActionButton? = mainLayout.findViewById(R.id.fab_url_download)
            if (fabUpload != null) mFabLayout!!.removeView(fabUpload)
            if (fabUrlDownload != null) mFabLayout!!.removeView(fabUrlDownload)
        }

        mSearchBarMover = SearchBarMover(this, mSearchBar, mRecyclerView, mSearchLayout)

        onUpdateUrlBuilder()

        // Restore state
        mStateHelper?.setState(GalleryStateHelper.STATE_NORMAL, false)
        mStateHelper?.setState(mRestoredState, false)
        mRestoredState = GalleryStateHelper.STATE_NORMAL

        if (!mHasFirstRefresh) {
            mHasFirstRefresh = true
            mHelper!!.firstRefresh()
        }

        guideQuickSearch()

        return view
    }

    private fun initHelpers(context: Context) {
        mFilterHelper = GalleryFilterHelper(object : GalleryFilterHelper.Callback {
            override fun getFilterFab(): FloatingActionButton? = mFloatingActionButton
        })

        mGoToHelper = GalleryGoToHelper(object : GalleryGoToHelper.Callback {
            override fun getHostContext(): Context? = ehContext
            override fun getContentHelper() = mHelper
            override fun getUrlBuilder(): ListUrlBuilder? = mUrlBuilder
            override fun getLayoutInflater(): LayoutInflater = this@GalleryListScene.layoutInflater
            override fun getString(resId: Int): String = this@GalleryListScene.getString(resId)
            override fun getString(resId: Int, vararg formatArgs: Any): String =
                this@GalleryListScene.getString(resId, *formatArgs)
        })

        mStateHelper = GalleryStateHelper(object : GalleryStateHelper.Callback {
            override fun getSearchBar(): SearchBar? = mSearchBar
            override fun getSearchBarMover(): SearchBarMover? = mSearchBarMover
            override fun getViewTransition(): ViewTransition? = mViewTransition
            override fun getSearchLayout(): SearchLayout? = mSearchLayout
            override fun getFabLayout(): FabLayout? = mFabLayout
            override fun getSearchFab(): View? = mSearchFab
            override fun getActionFabDrawable(): AddDeleteDrawable? = mActionFabDrawable
            override fun getLeftDrawable(): DrawerArrowDrawable? = mLeftDrawable
            override fun getRightDrawable(): AddDeleteDrawable? = mRightDrawable
            override fun setDrawerLockMode(mode: Int, gravity: Int) =
                this@GalleryListScene.setDrawerLockMode(mode, gravity)
        })

        mItemActionHelper = GalleryItemActionHelper(object : GalleryItemActionHelper.Callback {
            override fun getHostContext(): Context? = ehContext
            override fun getHostActivity(): Activity? = activity2
            override fun getLayoutInflater(): LayoutInflater = this@GalleryListScene.layoutInflater
            override fun getDownloadManager(): DownloadManager = mDownloadManager
            override fun startScene(announcer: Announcer) = this@GalleryListScene.startScene(announcer)
            override fun getString(resId: Int): String = this@GalleryListScene.getString(resId)
            override fun getString(resId: Int, vararg formatArgs: Any): String =
                this@GalleryListScene.getString(resId, *formatArgs)
            override fun buildChipGroup(gi: GalleryInfoUi?, chipGroup: ChipGroup): ChipGroup =
                mTagChipHelper?.buildChipGroup(gi, chipGroup) ?: chipGroup
        })

        mTagChipHelper = GalleryTagChipHelper(object : GalleryTagChipHelper.Callback {
            override fun getHostContext(): Context? = ehContext
            override fun requireContext(): Context = this@GalleryListScene.requireContext()
            override fun getLayoutInflater(): LayoutInflater = this@GalleryListScene.layoutInflater
            override fun isDrawersVisible(): Boolean = this@GalleryListScene.isDrawersVisible()
            override fun closeDrawer(gravity: Int) = this@GalleryListScene.closeDrawer(gravity)
            override fun getUrlBuilder(): ListUrlBuilder? = mUrlBuilder
            override fun getContentHelper() = mHelper
            override fun isFilterOpen(): Boolean = mFilterHelper?.filterOpen ?: false
            override fun buildFilterSearch(tagName: String): String =
                mFilterHelper?.searchTagBuild(tagName) ?: tagName
            override fun updateFilterDisplay() {
                mFilterHelper?.updateFilterIcon(mFilterHelper?.filterTagList?.size ?: 0)
            }
            override fun onUpdateUrlBuilder() = this@GalleryListScene.onUpdateUrlBuilder()
            override fun setState(state: Int) { mStateHelper?.setState(state) }
            override fun onItemClick(view: View?, gi: GalleryInfoUi?): Boolean =
                mItemActionHelper?.onItemClick(view, gi) ?: false
            override fun onItemLongClick(gi: GalleryInfoUi?, view: View): Boolean =
                mItemActionHelper?.onItemLongClick(gi, view) ?: false
            override fun dismissItemDialog() { mItemActionHelper?.dismissDialog() }
            override fun getBaseScene(): BaseScene = this@GalleryListScene
        })
        mTagChipHelper!!.setEhTags(EhTagDatabase.getInstance(context))

        mHelper = GalleryListDataHelper(object : GalleryListDataHelper.Callback {
            override fun getHostContext(): Context? = ehContext
            override fun getUrlBuilder(): ListUrlBuilder? = mUrlBuilder
            override fun getSortBy(): String = mSearchLayout?.sortBy ?: "date_added"
            override fun getSortOrder(): String = mSearchLayout?.sortOrder ?: "desc"
            override fun notifyAdapterDataSetChanged() {
                mAdapter?.notifyItemRangeChanged(0, mAdapter?.itemCount ?: 0)
            }
            override fun notifyAdapterItemRangeRemoved(positionStart: Int, itemCount: Int) {
                mAdapter?.notifyItemRangeRemoved(positionStart, itemCount)
            }
            override fun notifyAdapterItemRangeInserted(positionStart: Int, itemCount: Int) {
                mAdapter?.notifyItemRangeInserted(positionStart, itemCount)
            }
            override fun showSearchBar() { mSearchBarMover?.showSearchBar() }
            override fun showActionFab() { mStateHelper?.showActionFab() }
            override fun getString(resId: Int): String = this@GalleryListScene.getString(resId)
        })

        mSearchHelper = GallerySearchHelper(object : GallerySearchHelper.Callback {
            override fun getHostContext(): Context? = ehContext
            override fun getHostResources(): Resources? = resources2
            override fun navigateToScene(announcer: Announcer) = startScene(announcer)
            override fun getSearchState(): Int = mStateHelper?.state ?: GalleryStateHelper.STATE_NORMAL
            override fun setSearchState(state: Int) { mStateHelper?.setState(state) }
        })

        mUploadHelper = GalleryUploadHelper(object : GalleryUploadHelper.Callback {
            override fun showTip(message: String, length: Int) = this@GalleryListScene.showTip(message, length)
            override fun showTip(resId: Int, length: Int) = this@GalleryListScene.showTip(resId, length)
            override fun refreshList() { mHelper?.refresh() }
            override fun getHostActivity(): Activity? = activity2
            override fun getHostContext(): Context? = context
            override fun getHostString(resId: Int): String = getString(resId)
            override fun getHostString(resId: Int, vararg formatArgs: Any): String = getString(resId, *formatArgs)
            override fun startActivityForResult(intent: Intent, requestCode: Int) =
                this@GalleryListScene.startActivityForResult(intent, requestCode)
        })

        mDrawerHelper = GalleryDrawerHelper(object : GalleryDrawerHelper.Callback {
            override fun getHostContext(): Context? = ehContext
            override fun getHostActivity(): Activity? = activity2
            override fun getScene(): GalleryListScene = this@GalleryListScene
            override fun getSceneTag(): String? = tag
            override fun getUrlBuilder(): ListUrlBuilder? = mUrlBuilder
            override fun getEhTags(): EhTagDatabase? = mTagChipHelper?.getEhTags()
            override fun showTip(resId: Int, length: Int) = this@GalleryListScene.showTip(resId, length)
            override fun showTip(message: String, length: Int) = this@GalleryListScene.showTip(message, length)
            override fun getString(resId: Int): String = this@GalleryListScene.getString(resId)
            override fun getString(resId: Int, vararg formatArgs: Any): String =
                this@GalleryListScene.getString(resId, *formatArgs)
            override fun onTagClick(tagName: String) { mTagChipHelper?.onTagClick(tagName) }
        })
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
        if (mSearchBarMover != null) {
            mSearchBarMover!!.cancelAnimation()
            mSearchBarMover = null
        }
        if (mHelper != null) {
            mHelper!!.destroy()
            if (1 == mHelper!!.shownViewIndex) {
                mHasFirstRefresh = false
            }
        }
        if (mRecyclerView != null) {
            mRecyclerView!!.stopScroll()
            mRecyclerView = null
        }
        if (mFabLayout != null) {
            removeAboveSnackView(mFabLayout!!)
            mFabLayout = null
        }

        mAdapter = null
        mSearchLayout = null
        mSearchBar = null
        mSearchFab = null
        mViewTransition = null
        mLeftDrawable = null
        mRightDrawable = null
        mActionFabDrawable = null
        mUploadHelper = null
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
        mStateHelper?.setState(state)
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
        if (mAdapter != null) {
            mAdapter!!.setType(AppearanceSettings.getListMode())
            mAdapter!!.refreshColumnSize()
        }
        mDrawerHelper?.onResume()
    }

    override fun onBackPressed() {
        mTagChipHelper?.dismissPopup()
        if (mShowcaseView != null) {
            return
        }

        if (mFabLayout != null && mFabLayout!!.isExpanded) {
            mFabLayout!!.isExpanded = false
            return
        }

        val filterHelper = mFilterHelper
        if (filterHelper != null && filterHelper.filterOpen && filterHelper.removeLastFilterTag()) {
            mUrlBuilder!!.set(
                filterHelper.listToString(filterHelper.filterTagList),
                ListUrlBuilder.MODE_FILTER
            )
            filterHelper.updateFilterIcon(filterHelper.filterTagList.size)

            mUrlBuilder!!.pageIndex = 0
            onUpdateUrlBuilder()
            mHelper!!.refresh()
            mStateHelper?.setState(GalleryStateHelper.STATE_NORMAL)
            return
        }

        val currentState = mStateHelper?.state ?: GalleryStateHelper.STATE_NORMAL
        val handle = when (currentState) {
            GalleryStateHelper.STATE_SIMPLE_SEARCH, GalleryStateHelper.STATE_SEARCH -> {
                mStateHelper?.setState(GalleryStateHelper.STATE_NORMAL)
                true
            }
            GalleryStateHelper.STATE_SEARCH_SHOW_LIST -> {
                mStateHelper?.setState(GalleryStateHelper.STATE_SEARCH)
                true
            }
            else -> checkDoubleClickExit()
        }

        if (!handle) {
            finish()
        }
    }

    // EasyRecyclerView click listeners — delegate to helpers

    override fun onItemClick(parent: EasyRecyclerView, view: View, position: Int, id: Long): Boolean {
        return mItemActionHelper?.onItemClick(view, mHelper?.getDataAtEx(position)) ?: false
    }

    override fun onItemLongClick(parent: EasyRecyclerView, view: View, position: Int, id: Long): Boolean {
        return mItemActionHelper?.onItemLongClick(mHelper?.getDataAtEx(position), view) ?: false
    }

    // View.OnClickListener — search fab
    override fun onClick(v: View) {
        val currentState = mStateHelper?.state ?: GalleryStateHelper.STATE_NORMAL
        if (GalleryStateHelper.STATE_NORMAL != currentState && mSearchBar != null) {
            mSearchBar!!.applySearch(false)
            hideSoftInput()
        }
    }

    // FabLayout listeners

    override fun onClickPrimaryFab(view: FabLayout, fab: FloatingActionButton) {
        val currentState = mStateHelper?.state ?: GalleryStateHelper.STATE_NORMAL
        if (GalleryStateHelper.STATE_NORMAL == currentState) {
            view.toggle()
        }
    }

    override fun onClickSecondaryFab(view: FabLayout, fab: FloatingActionButton, position: Int) {
        if (mHelper == null) return

        when (position) {
            0 -> { // Toggle multi-tag search
                mFilterHelper?.toggleFilter()
            }
            1 -> { // Go to
                if (mHelper!!.canGoTo()) {
                    if (mUrlBuilder != null && mUrlBuilder!!.mode == ListUrlBuilder.MODE_TOP_LIST) return
                    mGoToHelper?.showGoToDialog()
                }
            }
            2 -> { // Refresh
                mHelper!!.refresh()
            }
            3 -> { // Random
                val gInfoL = mHelper!!.data
                if (gInfoL.isNullOrEmpty()) return
                mItemActionHelper?.onItemClick(null, gInfoL[(Math.random() * gInfoL.size).toInt()])
            }
            4 -> { // Upload archive (LRR only)
                mUploadHelper?.showUploadFilePicker()
            }
            5 -> { // URL download (LRR only)
                mUploadHelper?.showUrlDownloadDialog()
            }
        }

        view.isExpanded = false
    }

    @SuppressLint("RtlHardcoded")
    override fun onExpand(expanded: Boolean) {
        mStateHelper?.onFabExpand(expanded)
    }

    // SearchBar.Helper callbacks

    override fun onClickTitle() {
        val currentState = mStateHelper?.state ?: GalleryStateHelper.STATE_NORMAL
        if (currentState == GalleryStateHelper.STATE_NORMAL) {
            mStateHelper?.setState(GalleryStateHelper.STATE_SIMPLE_SEARCH)
        }
    }

    @SuppressLint("RtlHardcoded")
    override fun onClickLeftIcon() {
        if (mSearchBar == null) return
        if (mSearchBar!!.getState() == SearchBar.STATE_NORMAL) {
            toggleDrawer(Gravity.LEFT)
        } else {
            mStateHelper?.setState(GalleryStateHelper.STATE_NORMAL)
        }
    }

    override fun onClickRightIcon() {
        if (mSearchBar == null) return
        val currentState = mStateHelper?.state ?: GalleryStateHelper.STATE_NORMAL
        if (mSearchBar!!.getState() == SearchBar.STATE_NORMAL) {
            mStateHelper?.setState(GalleryStateHelper.STATE_SEARCH)
        } else {
            mSearchBar!!.setText("")
        }
    }

    override fun onSearchEditTextClick() {
        val currentState = mStateHelper?.state ?: GalleryStateHelper.STATE_NORMAL
        if (currentState == GalleryStateHelper.STATE_SEARCH) {
            mStateHelper?.setState(GalleryStateHelper.STATE_SEARCH_SHOW_LIST)
        }
    }

    override fun onApplySearch(query: String) {
        val urlBuilder = mUrlBuilder ?: return
        if (mHelper == null || mSearchLayout == null) return

        val cleanQuery = query.replace("\r", "").replace("\n", "")
        val currentState = mStateHelper?.state ?: GalleryStateHelper.STATE_NORMAL

        if (currentState == GalleryStateHelper.STATE_SEARCH ||
            currentState == GalleryStateHelper.STATE_SEARCH_SHOW_LIST
        ) {
            try {
                mSearchLayout!!.formatListUrlBuilder(urlBuilder, cleanQuery)
            } catch (e: EhException) {
                showTip(e.message ?: "", LENGTH_LONG)
                return
            }
        } else {
            val oldMode = urlBuilder.mode
            val newMode = if (oldMode == ListUrlBuilder.MODE_SUBSCRIPTION) {
                ListUrlBuilder.MODE_SUBSCRIPTION
            } else {
                ListUrlBuilder.MODE_NORMAL
            }
            urlBuilder.reset()
            urlBuilder.mode = newMode
            urlBuilder.keyword = cleanQuery
        }
        onUpdateUrlBuilder()
        mHelper!!.refresh()
        mStateHelper?.setState(GalleryStateHelper.STATE_NORMAL)
    }

    override fun onSearchEditTextBackPressed() {
        onBackPressed()
    }

    // SearchBar.OnStateChangeListener

    @SuppressLint("RtlHardcoded")
    override fun onStateChange(searchBar: SearchBar, newState: Int, oldState: Int, animation: Boolean) {
        mStateHelper?.onSearchBarStateChange(newState, oldState, animation)
    }

    // FastScroller.OnDragHandlerListener

    @SuppressLint("RtlHardcoded")
    override fun onStartDragHandler() {
        setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT)
    }

    @SuppressLint("RtlHardcoded")
    override fun onEndDragHandler() {
        setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT)
        mSearchBarMover?.returnSearchBarPosition()
    }

    // SearchLayout.Helper

    override fun onChangeSearchMode() {
        mSearchBarMover?.showSearchBar()
    }

    override fun onSelectImage() {
        val intent = Intent()
        intent.type = "image/*"
        intent.action = Intent.ACTION_GET_CONTENT
        startActivityForResult(
            Intent.createChooser(intent, getString(R.string.select_image)),
            REQUEST_CODE_SELECT_IMAGE
        )
    }

    override fun onSortChanged() {
        mHelper?.refresh()
    }

    // SearchBarMover.Helper

    override fun isValidView(recyclerView: RecyclerView): Boolean {
        val currentState = mStateHelper?.state ?: GalleryStateHelper.STATE_NORMAL
        return (currentState == GalleryStateHelper.STATE_NORMAL && recyclerView === mRecyclerView) ||
                (currentState == GalleryStateHelper.STATE_SEARCH && recyclerView === mSearchLayout)
    }

    override fun getValidRecyclerView(): RecyclerView? {
        val currentState = mStateHelper?.state ?: GalleryStateHelper.STATE_NORMAL
        return if (currentState == GalleryStateHelper.STATE_NORMAL ||
            currentState == GalleryStateHelper.STATE_SIMPLE_SEARCH
        ) {
            mRecyclerView
        } else {
            mSearchLayout
        }
    }

    override fun forceShowSearchBar(): Boolean {
        val currentState = mStateHelper?.state ?: GalleryStateHelper.STATE_NORMAL
        return currentState == GalleryStateHelper.STATE_SIMPLE_SEARCH ||
                currentState == GalleryStateHelper.STATE_SEARCH_SHOW_LIST
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (REQUEST_CODE_SELECT_IMAGE == requestCode) {
            if (Activity.RESULT_OK == resultCode && mSearchLayout != null && data != null) {
                mSearchLayout!!.setImageUri(data.data)
            }
        } else if (REQUEST_CODE_UPLOAD_ARCHIVE == requestCode) {
            if (Activity.RESULT_OK == resultCode && data?.data != null) {
                mUploadHelper?.handleUploadResult(data.data!!)
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
