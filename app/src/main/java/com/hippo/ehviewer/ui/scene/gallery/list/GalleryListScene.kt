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

import android.animation.Animator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.paging.PagingSource
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.github.amlcurran.showcaseview.ShowcaseView
import com.github.amlcurran.showcaseview.SimpleShowcaseEventListener
import com.github.amlcurran.showcaseview.targets.PointTarget
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.hippo.android.resource.AttrResources
import com.hippo.app.CheckBoxDialogBuilder
import com.hippo.app.EditTextDialogBuilder
import com.hippo.drawable.AddDeleteDrawable
import com.hippo.drawable.DrawerArrowDrawable
import com.hippo.drawerlayout.DrawerLayout
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.easyrecyclerview.FastScroller
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.FavouriteStatusRouter
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.callBack.SubscriptionCallback
import com.hippo.ehviewer.client.EhCacheKeyFactory
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.data.userTag.UserTag
import com.hippo.ehviewer.client.data.userTag.UserTagList
import com.hippo.ehviewer.client.exception.EhException
import com.hippo.ehviewer.client.lrr.LRRArchivePagingSource
import com.hippo.ehviewer.client.lrr.LRRAuthManager
import com.hippo.ehviewer.client.lrr.LRRClientProvider
import com.hippo.ehviewer.client.parser.GalleryListParser
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.QuickSearch
import com.hippo.ehviewer.download.DownloadInfoListener
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.settings.ReadingSettings
import com.hippo.ehviewer.ui.CommonOperations
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.ehviewer.ui.GalleryOpenHelper
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.dialog.SelectItemWithIconAdapter
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.ui.scene.EhCallback
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene
import com.hippo.ehviewer.util.TagTranslationUtil
import com.hippo.ehviewer.widget.GalleryInfoContentHelper
import com.hippo.ehviewer.widget.JumpDateSelector
import com.hippo.ehviewer.widget.SearchBar
import com.hippo.ehviewer.widget.SearchLayout
import com.hippo.refreshlayout.RefreshLayout
import com.hippo.ripple.Ripple
import com.hippo.scene.Announcer
import com.hippo.scene.SceneFragment
import com.hippo.util.AppHelper
import com.hippo.util.IoThreadPoolExecutor
import com.hippo.view.ViewTransition
import com.hippo.widget.ContentLayout
import com.hippo.widget.FabLayout
import com.hippo.widget.LoadImageViewNew
import com.hippo.widget.SearchBarMover
import com.hippo.lib.yorozuya.AnimationUtils
import com.hippo.lib.yorozuya.AssertUtils
import com.hippo.lib.yorozuya.SimpleAnimatorListener
import com.hippo.lib.yorozuya.ViewUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedList
import java.util.concurrent.ExecutorService

class GalleryListScene : BaseScene(),
    EasyRecyclerView.OnItemClickListener, EasyRecyclerView.OnItemLongClickListener,
    SearchBar.Helper, SearchBar.OnStateChangeListener, FastScroller.OnDragHandlerListener,
    SearchLayout.Helper, SearchBarMover.Helper, View.OnClickListener, FabLayout.OnClickFabListener,
    FabLayout.OnExpandListener, SubscriptionCallback {

    private var showReadProgress = false
    private var filterOpen = false
    private val filterTagList: MutableList<String> = ArrayList()

    /*---------------
     Whole life cycle
     ---------------*/
    private var mClient: EhClient? = null
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
    var mHelper: GalleryListHelper? = null
    private var mLeftDrawable: DrawerArrowDrawable? = null
    private var mRightDrawable: AddDeleteDrawable? = null
    private var mSearchBarMover: SearchBarMover? = null
    private var mActionFabDrawable: AddDeleteDrawable? = null
    private var popupWindow: PopupWindow? = null
    private var alertDialog: AlertDialog? = null
    private var jumpSelectorDialog: AlertDialog? = null
    lateinit var drawPager: ViewPager
    private lateinit var bookmarksView: View
    private var subscriptionView: View? = null

    private var mJumpDateSelector: JumpDateSelector? = null

    private var ehTags: EhTagDatabase? = null

    private lateinit var executorService: ExecutorService

    private var mBookmarksDraw: BookmarksDraw? = null
    private var mSubscriptionDraw: SubscriptionDraw? = null

    private var mUploadHelper: GalleryUploadHelper? = null

    private var mSearchHelper: GallerySearchHelper? = null

    private val mActionFabAnimatorListener: Animator.AnimatorListener? = object : SimpleAnimatorListener() {
        override fun onAnimationEnd(animation: Animator) {
            mFabLayout?.let { (it.primaryFab as View).visibility = View.INVISIBLE }
        }
    }

    private val mSearchFabAnimatorListener: Animator.AnimatorListener? = object : SimpleAnimatorListener() {
        override fun onAnimationEnd(animation: Animator) {
            mSearchFab?.visibility = View.INVISIBLE
        }
    }

    private var mHideActionFabSlop = 0
    private var mShowActionFab = true

    private val mOnScrollListener: RecyclerView.OnScrollListener? = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {}

        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy >= mHideActionFabSlop) {
                hideActionFab()
            } else if (dy <= -mHideActionFabSlop / 2) {
                showActionFab()
            }
        }
    }

    private var mState = STATE_NORMAL

    // Double click back exit
    private var mPressBackTime: Long = 0

    private var mHasFirstRefresh = false

    private var mNavCheckedId = 0

    private var popupWindowPosition = -1

    private var mShowcaseView: ShowcaseView? = null
    private var tagDialog: GalleryListSceneDialog? = null
    private lateinit var mDownloadManager: DownloadManager
    private var mDownloadInfoListener: DownloadInfoListener? = null
    private lateinit var mFavouriteStatusRouter: FavouriteStatusRouter
    private var mFavouriteStatusRouterListener: FavouriteStatusRouter.Listener? = null

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
        setState(STATE_NORMAL)
        mSearchBarMover?.showSearchBar()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val context = ehContext
        AssertUtils.assertNotNull(context)
        executorService = ServiceRegistry.appModule.executorService
        mClient = ServiceRegistry.clientModule.ehClient
        mDownloadManager = ServiceRegistry.dataModule.downloadManager
        mFavouriteStatusRouter = ServiceRegistry.dataModule.favouriteStatusRouter

        mDownloadInfoListener = object : DownloadInfoListener {
            override fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
                mAdapter?.notifyDataSetChanged()
            }

            override fun onReplace(newInfo: DownloadInfo, oldInfo: DownloadInfo) {}

            override fun onUpdate(info: DownloadInfo, list: List<DownloadInfo>, mWaitList: LinkedList<DownloadInfo>) {}

            override fun onUpdateAll() {}

            override fun onReload() {
                mAdapter?.notifyDataSetChanged()
            }

            override fun onChange() {
                mAdapter?.notifyDataSetChanged()
            }

            override fun onRenameLabel(from: String, to: String) {}

            override fun onRemove(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
                mAdapter?.notifyDataSetChanged()
            }

            override fun onUpdateLabels() {}
        }
        mDownloadManager.addDownloadInfoListener(mDownloadInfoListener)

        mFavouriteStatusRouterListener = FavouriteStatusRouter.Listener { _, _ ->
            mAdapter?.notifyDataSetChanged()
        }
        mFavouriteStatusRouter.addListener(mFavouriteStatusRouterListener!!)
        if (ehTags == null) {
            ehTags = EhTagDatabase.getInstance(context!!)
        }

        if (savedInstanceState == null) {
            onInit()
        } else {
            onRestore(savedInstanceState)
        }
        showReadProgress = ReadingSettings.getShowReadProgress()
    }

    fun onInit() {
        mUrlBuilder = ListUrlBuilder()
        handleArgs(arguments)
    }

    @Suppress("DEPRECATION")
    private fun onRestore(savedInstanceState: Bundle) {
        mHasFirstRefresh = savedInstanceState.getBoolean(KEY_HAS_FIRST_REFRESH)
        mUrlBuilder = savedInstanceState.getParcelable(KEY_LIST_URL_BUILDER)
        mState = savedInstanceState.getInt(KEY_STATE)
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
        outState.putInt(KEY_STATE, mState)
    }

    override fun onDestroy() {
        super.onDestroy()
        mClient = null
        mUrlBuilder = null
        mDownloadManager.removeDownloadInfoListener(mDownloadInfoListener)
        mFavouriteStatusRouterListener?.let { mFavouriteStatusRouter.removeListener(it) }
    }

    // Search bar hint, suggestion provider, title computation, keyword wrapping,
    // and LRR search result conversion are delegated to GallerySearchHelper.

    // Update search bar title, drawer checked item
    fun onUpdateUrlBuilder() {
        val builder = mUrlBuilder
        val resources = resources2
        if (resources == null || builder == null || mSearchLayout == null) {
            return
        }

        var keyword = builder.keyword
        val category = builder.category

        // LANraragi: no E-Hentai search modes

        // Update search edit text
        if (!keyword.isNullOrEmpty() && mSearchBar != null) {
            if (builder.mode == ListUrlBuilder.MODE_TAG) {
                keyword = GallerySearchHelper.wrapTagKeyword(keyword)
            }
            mSearchBar!!.setText(keyword)
            mSearchBar!!.cursorToEnd()
        }

        // Update title
        var title = GallerySearchHelper.getSuitableTitleForUrlBuilder(resources, builder, true)
        if (title == null) {
            title = resources.getString(R.string.search)
        }
        mSearchBar?.setTitle(title)

        // Update nav checked item
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
        mShowActionFab = true

        val mainLayout = ViewUtils.`$$`(view, R.id.main_layout)
        val contentLayout = ViewUtils.`$$`(mainLayout, R.id.content_layout) as ContentLayout
        mRecyclerView = contentLayout.recyclerView
        val fastScroller = contentLayout.fastScroller
        val refreshLayout: RefreshLayout = contentLayout.refreshLayout
        mSearchLayout = ViewUtils.`$$`(mainLayout, R.id.search_layout) as SearchLayout
        mSearchBar = ViewUtils.`$$`(mainLayout, R.id.search_bar) as SearchBar
        mFabLayout = ViewUtils.`$$`(mainLayout, R.id.fab_layout) as FabLayout
        mFloatingActionButton = ViewUtils.`$$`(mFabLayout, R.id.tag_filter) as FloatingActionButton

        onFilter(filterOpen, filterTagList.size)

        mSearchFab = ViewUtils.`$$`(mainLayout, R.id.search_fab)

        val paddingTopSB = resources.getDimensionPixelOffset(R.dimen.gallery_padding_top_search_bar)
        val paddingBottomFab = resources.getDimensionPixelOffset(R.dimen.gallery_padding_bottom_fab)

        mViewTransition = ViewTransition(contentLayout, mSearchLayout)

        mHelper = GalleryListHelper()
        mSearchHelper = GallerySearchHelper(object : GallerySearchHelper.Callback {
            override fun getHostContext(): Context? = ehContext
            override fun getHostResources(): Resources? = resources2
            override fun navigateToScene(announcer: Announcer) = startScene(announcer)
            override fun getSearchState(): Int = mState
            override fun setSearchState(state: Int) = setState(state)
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
        contentLayout.setHelper(mHelper)
        contentLayout.fastScroller.setOnDragHandlerListener(this)

        mAdapter = GalleryListAdapter(
            inflater, resources,
            mRecyclerView!!, AppearanceSettings.getListMode()
        )

        mAdapter!!.setThumbItemClickListener(object : GalleryAdapterNew.OnThumbItemClickListener {
            override fun onThumbItemClick(position: Int, view: View, gi: GalleryInfo?) {
                this@GalleryListScene.onThumbItemClick(position, view, gi)
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
        mRecyclerView!!.addOnScrollListener(mOnScrollListener!!)
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

        // Remove LRR-specific FABs when NOT connected to LANraragi.
        // When connected, FabLayout manages their visibility naturally during expand/collapse.
        if (LRRAuthManager.getServerUrl() == null) {
            val fabUpload: FloatingActionButton? = mainLayout.findViewById(R.id.fab_upload)
            val fabUrlDownload: FloatingActionButton? = mainLayout.findViewById(R.id.fab_url_download)
            if (fabUpload != null) mFabLayout!!.removeView(fabUpload)
            if (fabUrlDownload != null) mFabLayout!!.removeView(fabUrlDownload)
        }

        mSearchBarMover = SearchBarMover(this, mSearchBar, mRecyclerView, mSearchLayout)

        // Update list url builder
        onUpdateUrlBuilder()

        // Restore state
        val newState = mState
        mState = STATE_NORMAL
        setState(newState, false)

        // Only refresh for the first time
        if (!mHasFirstRefresh) {
            mHasFirstRefresh = true
            mHelper!!.firstRefresh()
        }

        guideQuickSearch()

        return view
    }

    private fun onThumbItemClick(position: Int, view: View, gi: GalleryInfo?) {
        val thumb: LoadImageViewNew = view.findViewById(R.id.thumb_new)
        if (thumb.mFailed) {
            thumb.load()
            return
        }

        if (popupWindow != null) {
            if (popupWindowPosition == position) {
                popupWindowPosition = -1
                popupWindow!!.dismiss()
                return
            }
            popupWindowPosition = -1
            popupWindow!!.dismiss()
        }

        val tgList = gi?.tgList
        if (gi != null && (tgList == null || tgList.isEmpty())) {
            onItemClick(view, gi)
            return
        }

        if (position != popupWindowPosition) {
            @SuppressLint("InflateParams")
            val popView = layoutInflater.inflate(R.layout.list_thumb_popupwindow, null) as LinearLayout
            val tagFlowLayout = buildChipGroup(gi, popView.findViewById(R.id.tab_tag_flow))

            popupWindow = PopupWindow(popView, view.width - thumb.width, thumb.height)
            popupWindow!!.isOutsideTouchable = true
            popupWindow!!.animationStyle = R.style.PopupWindow

            tagFlowLayout.setOnClickListener {
                popupWindowPosition = -1
                popupWindow!!.dismiss()
                onItemClick(view, gi)
            }
            tagFlowLayout.setOnLongClickListener { onItemLongClick(gi, view) }
            val location = IntArray(2)
            thumb.getLocationOnScreen(location)
            popupWindow!!.showAtLocation(thumb, Gravity.NO_GRAVITY, location[0] + thumb.width, location[1])
            popupWindowPosition = position
        }
    }

    private fun buildChipGroup(gi: GalleryInfo?, tagFlowLayout: ChipGroup): ChipGroup {
        val colorTag = AttrResources.getAttrColor(requireContext(), R.attr.tagBackgroundColor)
        val tgList = gi?.tgList
        if (tgList == null) {
            val tagName = getString(R.string.lrr_no_preview_tags)
            @SuppressLint("InflateParams")
            val chip = layoutInflater.inflate(R.layout.item_chip_tag, null) as Chip
            chip.chipBackgroundColor = ColorStateList.valueOf(colorTag)
            chip.setTextColor(Color.WHITE)
            if (AppearanceSettings.getShowTagTranslations()) {
                if (ehTags == null) {
                    ehTags = EhTagDatabase.getInstance(requireContext())
                }
                chip.text = TagTranslationUtil.getTagCNBody(tagName.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray(), ehTags)
            } else {
                val tagSplit = tagName.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                chip.text = if (tagSplit.size > 1) tagSplit[1] else tagSplit[0]
            }
            tagFlowLayout.addView(chip, 0)
            return tagFlowLayout
        }
        for (i in tgList.indices) {
            val tagName = tgList[i]
            @SuppressLint("InflateParams")
            val chip = layoutInflater.inflate(R.layout.item_chip_tag, null) as Chip
            chip.chipBackgroundColor = ColorStateList.valueOf(colorTag)
            chip.setTextColor(Color.WHITE)
            if (AppearanceSettings.getShowTagTranslations()) {
                if (ehTags == null) {
                    ehTags = EhTagDatabase.getInstance(requireContext())
                }
                chip.text = TagTranslationUtil.getTagCNBody(tagName.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray(), ehTags)
            } else {
                val tagSplit = tagName.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                chip.text = if (tagSplit.size > 1) tagSplit[1] else tagSplit[0]
            }
            chip.setOnClickListener { onTagClick(tagName) }
            chip.setOnLongClickListener { onTagLongClick(tagName) }
            tagFlowLayout.addView(chip, i)
        }

        return tagFlowLayout
    }

    private fun onTagLongClick(tagName: String): Boolean {
        if (tagDialog == null) {
            tagDialog = GalleryListSceneDialog(this)
        }
        if (ehTags == null) {
            ehTags = EhTagDatabase.getInstance(requireContext())
        }
        tagDialog!!.setTagName(tagName)
        tagDialog!!.showTagLongPressDialog(ehTags)
        return true
    }

    private fun onTagClick(tagName: String) {
        if (isDrawersVisible()) {
            closeDrawer(Gravity.RIGHT)
        }
        if (mHelper == null || mUrlBuilder == null) {
            return
        }
        popupWindowPosition = -1
        popupWindow?.dismiss()
        alertDialog?.dismiss()

        if (filterOpen) {
            mUrlBuilder!!.set(searchTagBuild(tagName), ListUrlBuilder.MODE_FILTER)
            onFilter(filterOpen, filterTagList.size)
        } else {
            mUrlBuilder!!.set(tagName)
        }

        mUrlBuilder!!.pageIndex = 0
        onUpdateUrlBuilder()
        mHelper!!.refresh()
        setState(STATE_NORMAL)
    }

    private fun searchTagBuild(tagName: String): String {
        val list = tagName.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

        val key = if (list.size == 2) {
            list[1]
        } else {
            list[0]
        }

        if (!filterTagList.contains(key)) {
            filterTagList.add(key)
        }
        return listToString(filterTagList)
    }

    private fun listToString(list: List<String>): String {
        val result = StringBuilder()
        for (i in list.indices) {
            if (i == 0) {
                result.append(list[i])
            } else {
                result.append("  ").append(list[i])
            }
        }
        return result.toString()
    }

    private fun guideQuickSearch() {
        val activity = activity2
        if (activity == null || !Settings.getGuideQuickSearch()) {
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
                    Settings.putGuideQuickSearch(false)
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

    fun showQuickSearchTipDialog(
        list: List<QuickSearch>,
        adapter: ArrayAdapter<QuickSearch>, listView: ListView, tip: TextView
    ) {
        val context = ehContext ?: return
        val builder = CheckBoxDialogBuilder(
            context, getString(R.string.add_quick_search_tip), getString(R.string.get_it), false
        )
        builder.setTitle(R.string.readme)
        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            if (builder.isChecked) {
                Settings.putQuickSearchTip(false)
            }
            showAddQuickSearchDialog(list, adapter, listView, tip)
        }.show()
    }

    fun showAddQuickSearchDialog(
        list: List<QuickSearch>,
        adapter: ArrayAdapter<QuickSearch>, listView: ListView, tip: TextView
    ) {
        val translation = AppearanceSettings.getShowTagTranslations()
        val context = ehContext
        val urlBuilder = mUrlBuilder
        if (context == null || urlBuilder == null) {
            return
        }

        // Can't add image search as quick search
        if (ListUrlBuilder.MODE_IMAGE_SEARCH == urlBuilder.mode) {
            showTip(R.string.image_search_not_quick_search, LENGTH_LONG)
            return
        }

        // Check duplicate
        for (q in list) {
            if (urlBuilder.equalsQuickSearch(q)) {
                showTip(getString(R.string.duplicate_quick_search, q.name), LENGTH_LONG)
                return
            }
        }

        val builder = EditTextDialogBuilder(
            context,
            GallerySearchHelper.getSuitableTitleForUrlBuilder(context.resources, urlBuilder, false),
            getString(R.string.quick_search)
        )
        builder.setTitle(R.string.add_quick_search_dialog_title)
        builder.setPositiveButton(android.R.string.ok, null)
        val dialog = builder.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val text = builder.text.trim()

            // Check name empty
            if (TextUtils.isEmpty(text)) {
                builder.setError(getString(R.string.name_is_empty))
                return@setOnClickListener
            }

            // Check name duplicate
            for (q in list) {
                if (text == q.name) {
                    builder.setError(getString(R.string.duplicate_name))
                    return@setOnClickListener
                }
            }

            builder.setError(null)
            dialog.dismiss()
            val quickSearch = urlBuilder.toQuickSearch()

            if (translation) {
                if (ehTags == null) {
                    ehTags = EhTagDatabase.getInstance(context)
                }
                val parts = text.split("  ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val newText = StringBuilder()
                for (part in parts) {
                    val tags = part.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                    for (j in tags.indices) {
                        tags[j] = tags[j].replace("\"", "").replace("$", "")
                    }
                    val trans = TagTranslationUtil.getTagCN(tags, ehTags)
                    if (newText.isEmpty()) {
                        newText.append(trans)
                    } else {
                        newText.append("  ").append(trans)
                    }
                }
                quickSearch.name = newText.toString()
            } else {
                quickSearch.name = text
            }
            val a = activity
            IoThreadPoolExecutor.instance.execute {
                EhDB.insertQuickSearch(quickSearch)
                a?.runOnUiThread {
                    @Suppress("UNCHECKED_CAST")
                    (list as MutableList<QuickSearch>).add(quickSearch)
                    adapter.notifyDataSetChanged()
                    if (list.isEmpty()) {
                        tip.visibility = View.VISIBLE
                        listView.visibility = View.GONE
                    } else {
                        tip.visibility = View.GONE
                        listView.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    @SuppressLint("RtlHardcoded", "NonConstantResourceId")
    override fun onCreateDrawerView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.drawer_list, container, false)

        drawPager = view.findViewById(R.id.drawer_list_pager)

        bookmarksView = bookmarksViewBuild(inflater)
        subscriptionView = subscriptionViewBuild(inflater)

        val views: MutableList<View> = ArrayList()
        views.add(bookmarksView)
        subscriptionView?.let { views.add(it) }

        val pagerAdapter = DrawViewPagerAdapter(views)
        drawPager.adapter = pagerAdapter

        return view
    }

    @SuppressLint("RtlHardcoded", "NonConstantResourceId")
    private fun bookmarksViewBuild(inflater: LayoutInflater): View {
        val context = ehContext ?: return View(null)
        mBookmarksDraw = BookmarksDraw(ehContext!!, inflater, ehTags)
        return mBookmarksDraw!!.onCreate(this)
    }

    private fun subscriptionViewBuild(inflater: LayoutInflater): View? {
        val context = ehContext ?: return null
        mSubscriptionDraw = SubscriptionDraw(ehContext!!, inflater, mClient!!, tag ?: "", ehTags)
        return mSubscriptionDraw!!.onCreate(drawPager, activity2!!, this)
    }

    override fun setTagList(result: UserTagList?) {
        result?.let { mSubscriptionDraw?.setUserTagList(it) }
    }

    override fun onSubscriptionItemClick(name: String) {
        onTagClick(name)
    }

    override fun getAddTagName(userTagList: UserTagList?): String? {
        val context = ehContext
        val urlBuilder = mUrlBuilder
        if (context == null || urlBuilder == null) {
            return null
        }

        // Can't add image search as quick search
        if (ListUrlBuilder.MODE_IMAGE_SEARCH == urlBuilder.mode) {
            showTip(R.string.image_search_not_quick_search, LENGTH_LONG)
            return null
        }

        if (urlBuilder.keyword == null) {
            return null
        }

        if (userTagList?.userTags == null) {
            return GallerySearchHelper.getSuitableTitleForUrlBuilder(ehContext!!.resources, urlBuilder, false)
        }
        // Check duplicate
        for (q in userTagList.userTags) {
            if (urlBuilder.equalKeyWord(q.tagName)) {
                showTip(getString(R.string.duplicate_quick_search, q.tagName), LENGTH_LONG)
                return null
            }
        }
        return GallerySearchHelper.getSuitableTitleForUrlBuilder(ehContext!!.resources, urlBuilder, false)
    }

    private fun checkDoubleClickExit(): Boolean {
        if (stackIndex != 0) {
            return false
        }

        val time = System.currentTimeMillis()
        return if (time - mPressBackTime > BACK_PRESSED_INTERVAL) {
            // It is the last scene
            mPressBackTime = time
            showTip(R.string.press_twice_exit, LENGTH_SHORT)
            true
        } else {
            false
        }
    }

    override fun onResume() {
        super.onResume()
        // Apply list mode change immediately when returning from settings
        if (mAdapter != null) {
            mAdapter!!.setType(AppearanceSettings.getListMode())
            mAdapter!!.refreshColumnSize()
        }
        if (mBookmarksDraw == null) {
            return
        }
        mBookmarksDraw!!.resume()
        if (mSubscriptionDraw == null) {
            return
        }
        mSubscriptionDraw!!.resume()
    }

    override fun onBackPressed() {
        popupWindow?.dismiss()
        if (mShowcaseView != null) {
            return
        }

        if (mFabLayout != null && mFabLayout!!.isExpanded) {
            mFabLayout!!.isExpanded = false
            return
        }

        if (filterOpen && filterTagList.size > 1) {
            filterTagList.removeAt(filterTagList.size - 1)
            mUrlBuilder!!.set(listToString(filterTagList), ListUrlBuilder.MODE_FILTER)
            onFilter(filterOpen, filterTagList.size)

            mUrlBuilder!!.pageIndex = 0
            onUpdateUrlBuilder()
            mHelper!!.refresh()
            setState(STATE_NORMAL)
            return
        }

        val handle = when (mState) {
            STATE_SIMPLE_SEARCH, STATE_SEARCH -> {
                setState(STATE_NORMAL)
                true
            }
            STATE_SEARCH_SHOW_LIST -> {
                setState(STATE_SEARCH)
                true
            }
            else -> checkDoubleClickExit()
        }

        if (!handle) {
            finish()
        }
    }

    override fun onItemClick(parent: EasyRecyclerView, view: View, position: Int, id: Long): Boolean {
        return onItemClick(view, mHelper!!.getDataAtEx(position))
    }

    fun onItemClick(view: View?, gi: GalleryInfo?): Boolean {
        if (mHelper == null || mRecyclerView == null) {
            return false
        }
        if (gi == null) {
            return true
        }
        alertDialog?.dismiss()

        val args = Bundle()
        args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GALLERY_INFO)
        args.putParcelable(GalleryDetailScene.KEY_GALLERY_INFO, gi)
        val announcer = Announcer(GalleryDetailScene::class.java).setArgs(args)
        val thumb: View?
        if (view != null) {
            thumb = view.findViewById(R.id.thumb)
            if (thumb != null) {
                announcer.setTranHelper(EnterGalleryDetailTransaction(thumb))
            }
        }
        startScene(announcer)
        return true
    }

    override fun onItemLongClick(parent: EasyRecyclerView, view: View, position: Int, id: Long): Boolean {
        return onItemLongClick(mHelper!!.getDataAtEx(position), view)
    }

    fun onItemLongClick(gi: GalleryInfo?, view: View): Boolean {
        val context = ehContext
        val activity = activity2
        if (context == null || activity == null || mHelper == null) {
            return false
        }

        if (gi == null) {
            return true
        }

        val downloaded = mDownloadManager.getDownloadState(gi.gid) != DownloadInfo.STATE_INVALID
        val favourited = gi.favoriteSlot != -2

        val items = arrayOf<CharSequence>(
            context.getString(R.string.read),
            context.getString(if (downloaded) R.string.delete_downloads else R.string.download),
            context.getString(if (favourited) R.string.remove_from_favourites else R.string.add_to_favourites),
        )

        val icons = intArrayOf(
            R.drawable.v_book_open_x24,
            if (downloaded) R.drawable.v_delete_x24 else R.drawable.v_download_x24,
            if (favourited) R.drawable.v_heart_broken_x24 else R.drawable.v_heart_x24,
        )

        @SuppressLint("InflateParams")
        val linearLayout = layoutInflater2.inflate(R.layout.gallery_item_dialog_coustom_title, null) as LinearLayout

        linearLayout.setOnClickListener { onItemClick(view, gi) }

        val imageViewNew: LoadImageViewNew = linearLayout.findViewById(R.id.dialog_thumb)
        imageViewNew.load(EhCacheKeyFactory.getThumbKey(gi.gid), gi.thumb)
        imageViewNew.setOnClickListener { onItemClick(view, gi) }

        buildChipGroup(gi, linearLayout.findViewById(R.id.tab_tag_flow))

        val textView: TextView = linearLayout.findViewById(R.id.title_text)
        textView.text = EhUtils.getSuitableTitle(gi)
        textView.setOnClickListener {
            AppHelper.copyPlainText(EhUtils.getSuitableTitle(gi), requireContext())
            val toast = Toast.makeText(ehContext, R.string.lrr_title_copied, Toast.LENGTH_SHORT)
            toast.setGravity(Gravity.CENTER, 0, 0)
            toast.show()
        }

        alertDialog = AlertDialog.Builder(context)
            .setCustomTitle(linearLayout)
            .setAdapter(SelectItemWithIconAdapter(context, items, icons)) { _, which ->
                when (which) {
                    0 -> { // Read
                        val intent = GalleryOpenHelper.buildReadIntent(activity, gi)
                        startActivity(intent)
                    }
                    1 -> { // Download
                        if (downloaded) {
                            AlertDialog.Builder(context)
                                .setTitle(R.string.download_remove_dialog_title)
                                .setMessage(getString(R.string.download_remove_dialog_message, gi.title))
                                .setPositiveButton(android.R.string.ok) { _, _ -> mDownloadManager.deleteDownload(gi.gid) }
                                .show()
                        } else {
                            CommonOperations.startDownload(activity, gi, false)
                        }
                    }
                    2 -> { // Favorites
                        if (favourited) {
                            CommonOperations.removeFromFavorites(
                                activity, gi,
                                RemoveFromFavoriteListener(context, activity.stageId, tag)
                            )
                        } else {
                            CommonOperations.addToFavorites(
                                activity, gi,
                                AddToFavoriteListener(context, activity.stageId, tag), false
                            )
                        }
                    }
                }
            }.show()
        return true
    }

    override fun onClick(v: View) {
        if (STATE_NORMAL != mState && mSearchBar != null) {
            mSearchBar!!.applySearch(false)
            hideSoftInput()
        }
    }

    override fun onClickPrimaryFab(view: FabLayout, fab: FloatingActionButton) {
        if (STATE_NORMAL == mState) {
            view.toggle()
        }
    }

    private fun showGoToDialog() {
        val context = ehContext ?: return
        if (mHelper == null) return

        if (mHelper!!.mPages < 0) {
            showDateJumpDialog(context)
        } else {
            showPageJumpDialog(context)
        }
    }

    private fun showDateJumpDialog(context: Context) {
        if (mHelper == null) return
        if (mHelper!!.nextHref == null || mHelper!!.nextHref.isEmpty()) {
            Toast.makeText(ehContext, R.string.gallery_list_no_more_data, Toast.LENGTH_LONG).show()
            return
        }
        if (jumpSelectorDialog == null) {
            val linearLayout = layoutInflater.inflate(R.layout.gallery_list_date_jump_dialog, null) as LinearLayout
            mJumpDateSelector = linearLayout.findViewById(R.id.gallery_list_jump_date)
            mJumpDateSelector!!.setOnTimeSelectedListener { urlAppend -> onTimeSelected(urlAppend) }
            jumpSelectorDialog = AlertDialog.Builder(context).setView(linearLayout).create()
        }
        mJumpDateSelector!!.setFoundMessage(mHelper!!.resultCount)
        jumpSelectorDialog!!.show()
    }

    private fun showPageJumpDialog(context: Context) {
        val page = mHelper!!.pageForTop
        val pages = mHelper!!.pages
        val hint = getString(R.string.go_to_hint, page + 1, pages)
        val builder = EditTextDialogBuilder(context, null, hint)
        builder.editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val dialog = builder.setTitle(R.string.go_to)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            if (mHelper == null) {
                dialog.dismiss()
                return@setOnClickListener
            }

            val text = builder.text.trim()
            val goTo: Int
            try {
                goTo = text.toInt() - 1
            } catch (e: NumberFormatException) {
                builder.setError(getString(R.string.error_invalid_number))
                return@setOnClickListener
            }
            if (goTo < 0 || goTo >= pages) {
                builder.setError(getString(R.string.error_out_of_range))
                return@setOnClickListener
            }
            builder.setError(null)
            mHelper!!.goTo(goTo)
            AppHelper.hideSoftInput(dialog)
            dialog.dismiss()
        }
    }

    private fun onTimeSelected(urlAppend: String) {
        Log.d(TAG, urlAppend)
        if (urlAppend.isEmpty() || mHelper == null || jumpSelectorDialog == null || mUrlBuilder == null) {
            return
        }
        jumpSelectorDialog!!.dismiss()
        mHelper!!.nextHref = mUrlBuilder!!.jumpHrefBuild(mHelper!!.nextHref, urlAppend)
        mHelper!!.goTo(-996)
    }

    override fun onClickSecondaryFab(view: FabLayout, fab: FloatingActionButton, position: Int) {
        if (mHelper == null) {
            return
        }

        when (position) {
            0 -> { // Toggle multi-tag search
                filterOpen = !filterOpen
                onFilter(filterOpen, filterTagList.size)
            }
            1 -> { // Go to
                if (mHelper!!.canGoTo()) {
                    if (mUrlBuilder != null && mUrlBuilder!!.mode == ListUrlBuilder.MODE_TOP_LIST) return
                    showGoToDialog()
                }
            }
            2 -> { // Refresh
                mHelper!!.refresh()
            }
            3 -> { // Random
                val gInfoL = mHelper!!.data
                if (gInfoL.isNullOrEmpty()) return
                onItemClick(null, gInfoL[(Math.random() * gInfoL.size).toInt()])
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

    fun onFilter(open: Boolean, num: Int) {
        if (mFloatingActionButton == null) return
        if (!open) {
            mFloatingActionButton!!.setImageResource(R.drawable.ic_baseline_filter_none_24)
            filterTagList.clear()
            return
        }

        val resId = when (num) {
            0 -> R.drawable.ic_baseline_filter_24
            1 -> R.drawable.ic_baseline_filter_1_24
            2 -> R.drawable.ic_baseline_filter_2_24
            3 -> R.drawable.ic_baseline_filter_3_24
            4 -> R.drawable.ic_baseline_filter_4_24
            5 -> R.drawable.ic_baseline_filter_5_24
            6 -> R.drawable.ic_baseline_filter_6_24
            7 -> R.drawable.ic_baseline_filter_7_24
            8 -> R.drawable.ic_baseline_filter_8_24
            9 -> R.drawable.ic_baseline_filter_9_24
            else -> R.drawable.ic_baseline_filter_9_plus_24
        }
        mFloatingActionButton!!.setImageResource(resId)
    }

    @SuppressLint("RtlHardcoded")
    override fun onExpand(expanded: Boolean) {
        if (mActionFabDrawable == null) return

        if (expanded) {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT)
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT)
            mActionFabDrawable!!.setDelete(ANIMATE_TIME)
        } else {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.LEFT)
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT)
            mActionFabDrawable!!.setAdd(ANIMATE_TIME)
        }
    }

    private fun showActionFab() {
        if (mFabLayout != null && STATE_NORMAL == mState && !mShowActionFab) {
            mShowActionFab = true
            val fab: View = mFabLayout!!.primaryFab
            fab.visibility = View.VISIBLE
            fab.rotation = -45.0f
            fab.animate().scaleX(1.0f).scaleY(1.0f).rotation(0.0f).setListener(null)
                .setDuration(ANIMATE_TIME).setStartDelay(0L)
                .setInterpolator(AnimationUtils.FAST_SLOW_INTERPOLATOR).start()
        }
    }

    private fun hideActionFab() {
        if (mFabLayout != null && STATE_NORMAL == mState && mShowActionFab) {
            mShowActionFab = false
            val fab: View = mFabLayout!!.primaryFab
            fab.animate().scaleX(0.0f).scaleY(0.0f).setListener(mActionFabAnimatorListener)
                .setDuration(ANIMATE_TIME).setStartDelay(0L)
                .setInterpolator(AnimationUtils.SLOW_FAST_INTERPOLATOR).start()
        }
    }

    private fun selectSearchFab(animation: Boolean) {
        if (mFabLayout == null || mSearchFab == null) return

        mShowActionFab = false

        if (animation) {
            val fab: View = mFabLayout!!.primaryFab
            val delay: Long
            if (View.INVISIBLE == fab.visibility) {
                delay = 0L
            } else {
                delay = ANIMATE_TIME
                fab.animate().scaleX(0.0f).scaleY(0.0f).setListener(mActionFabAnimatorListener)
                    .setDuration(ANIMATE_TIME).setStartDelay(0L)
                    .setInterpolator(AnimationUtils.SLOW_FAST_INTERPOLATOR).start()
            }
            mSearchFab!!.visibility = View.VISIBLE
            mSearchFab!!.rotation = -45.0f
            mSearchFab!!.animate().scaleX(1.0f).scaleY(1.0f).rotation(0.0f).setListener(null)
                .setDuration(ANIMATE_TIME).setStartDelay(delay)
                .setInterpolator(AnimationUtils.FAST_SLOW_INTERPOLATOR).start()
        } else {
            mFabLayout!!.setExpanded(false, false)
            val fab: View = mFabLayout!!.primaryFab
            fab.visibility = View.INVISIBLE
            fab.scaleX = 0.0f
            fab.scaleY = 0.0f
            mSearchFab!!.visibility = View.VISIBLE
            mSearchFab!!.scaleX = 1.0f
            mSearchFab!!.scaleY = 1.0f
        }
    }

    private fun selectActionFab(animation: Boolean) {
        if (mFabLayout == null || mSearchFab == null) return

        mShowActionFab = true

        if (animation) {
            val delay: Long
            if (View.INVISIBLE == mSearchFab!!.visibility) {
                delay = 0L
            } else {
                delay = ANIMATE_TIME
                mSearchFab!!.animate().scaleX(0.0f).scaleY(0.0f).setListener(mSearchFabAnimatorListener)
                    .setDuration(ANIMATE_TIME).setStartDelay(0L)
                    .setInterpolator(AnimationUtils.SLOW_FAST_INTERPOLATOR).start()
            }
            val fab: View = mFabLayout!!.primaryFab
            fab.visibility = View.VISIBLE
            fab.rotation = -45.0f
            fab.animate().scaleX(1.0f).scaleY(1.0f).rotation(0.0f).setListener(null)
                .setDuration(ANIMATE_TIME).setStartDelay(delay)
                .setInterpolator(AnimationUtils.FAST_SLOW_INTERPOLATOR).start()
        } else {
            mFabLayout!!.setExpanded(false, false)
            val fab: View = mFabLayout!!.primaryFab
            fab.visibility = View.VISIBLE
            fab.scaleX = 1.0f
            fab.scaleY = 1.0f
            mSearchFab!!.visibility = View.INVISIBLE
            mSearchFab!!.scaleX = 0.0f
            mSearchFab!!.scaleY = 0.0f
        }
    }

    fun setState(state: Int) {
        setState(state, true)
    }

    private fun setState(state: Int, animation: Boolean) {
        if (mSearchBar == null || mSearchBarMover == null ||
            mViewTransition == null || mSearchLayout == null
        ) {
            return
        }

        if (mState != state) {
            val oldState = mState
            mState = state

            when (oldState) {
                STATE_NORMAL -> {
                    when (state) {
                        STATE_SIMPLE_SEARCH -> {
                            mSearchBar!!.setState(SearchBar.STATE_SEARCH_LIST, animation)
                            mSearchBarMover!!.returnSearchBarPosition()
                            selectSearchFab(animation)
                        }
                        STATE_SEARCH -> {
                            mViewTransition!!.showView(1, animation)
                            mSearchLayout!!.scrollSearchContainerToTop()
                            mSearchBar!!.setState(SearchBar.STATE_SEARCH, animation)
                            mSearchBarMover!!.returnSearchBarPosition()
                            selectSearchFab(animation)
                        }
                        STATE_SEARCH_SHOW_LIST -> {
                            mViewTransition!!.showView(1, animation)
                            mSearchLayout!!.scrollSearchContainerToTop()
                            mSearchBar!!.setState(SearchBar.STATE_SEARCH_LIST, animation)
                            mSearchBarMover!!.returnSearchBarPosition()
                            selectSearchFab(animation)
                        }
                    }
                }
                STATE_SIMPLE_SEARCH -> {
                    when (state) {
                        STATE_NORMAL -> {
                            mSearchBar!!.setState(SearchBar.STATE_NORMAL, animation)
                            mSearchBarMover!!.returnSearchBarPosition()
                            selectActionFab(animation)
                        }
                        STATE_SEARCH -> {
                            mViewTransition!!.showView(1, animation)
                            mSearchLayout!!.scrollSearchContainerToTop()
                            mSearchBar!!.setState(SearchBar.STATE_SEARCH, animation)
                            mSearchBarMover!!.returnSearchBarPosition()
                        }
                        STATE_SEARCH_SHOW_LIST -> {
                            mViewTransition!!.showView(1, animation)
                            mSearchLayout!!.scrollSearchContainerToTop()
                            mSearchBar!!.setState(SearchBar.STATE_SEARCH_LIST, animation)
                            mSearchBarMover!!.returnSearchBarPosition()
                        }
                    }
                }
                STATE_SEARCH -> {
                    when (state) {
                        STATE_NORMAL -> {
                            mViewTransition!!.showView(0, animation)
                            mSearchBar!!.setState(SearchBar.STATE_NORMAL, animation)
                            mSearchBarMover!!.returnSearchBarPosition()
                            selectActionFab(animation)
                        }
                        STATE_SIMPLE_SEARCH -> {
                            mViewTransition!!.showView(0, animation)
                            mSearchBar!!.setState(SearchBar.STATE_SEARCH_LIST, animation)
                            mSearchBarMover!!.returnSearchBarPosition()
                        }
                        STATE_SEARCH_SHOW_LIST -> {
                            mSearchBar!!.setState(SearchBar.STATE_SEARCH_LIST, animation)
                            mSearchBarMover!!.returnSearchBarPosition()
                        }
                    }
                }
                STATE_SEARCH_SHOW_LIST -> {
                    when (state) {
                        STATE_NORMAL -> {
                            mViewTransition!!.showView(0, animation)
                            mSearchBar!!.setState(SearchBar.STATE_NORMAL, animation)
                            mSearchBarMover!!.returnSearchBarPosition()
                            selectActionFab(animation)
                        }
                        STATE_SIMPLE_SEARCH -> {
                            mViewTransition!!.showView(0, animation)
                            mSearchBar!!.setState(SearchBar.STATE_SEARCH_LIST, animation)
                            mSearchBarMover!!.returnSearchBarPosition()
                        }
                        STATE_SEARCH -> {
                            mSearchBar!!.setState(SearchBar.STATE_SEARCH, animation)
                            mSearchBarMover!!.returnSearchBarPosition()
                        }
                    }
                }
            }
        }
    }

    override fun onClickTitle() {
        if (mState == STATE_NORMAL) {
            setState(STATE_SIMPLE_SEARCH)
        }
    }

    @SuppressLint("RtlHardcoded")
    override fun onClickLeftIcon() {
        if (mSearchBar == null) return

        if (mSearchBar!!.getState() == SearchBar.STATE_NORMAL) {
            toggleDrawer(Gravity.LEFT)
        } else {
            setState(STATE_NORMAL)
        }
    }

    override fun onClickRightIcon() {
        if (mSearchBar == null) return

        if (mSearchBar!!.getState() == SearchBar.STATE_NORMAL) {
            setState(STATE_SEARCH)
        } else {
            // Clear
            mSearchBar!!.setText("")
        }
    }

    override fun onSearchEditTextClick() {
        if (mState == STATE_SEARCH) {
            setState(STATE_SEARCH_SHOW_LIST)
        }
    }

    override fun onApplySearch(query: String) {
        val urlBuilder = mUrlBuilder ?: return
        if (mHelper == null || mSearchLayout == null) {
            return
        }

        // Filter newline characters from search text to avoid breaking search syntax
        val cleanQuery = query.replace("\r", "").replace("\n", "")

        if (mState == STATE_SEARCH || mState == STATE_SEARCH_SHOW_LIST) {
            try {
                mSearchLayout!!.formatListUrlBuilder(urlBuilder, cleanQuery)
            } catch (e: EhException) {
                showTip(e.message ?: "", LENGTH_LONG)
                return
            }
        } else {
            val oldMode = urlBuilder.mode
            // If it's MODE_SUBSCRIPTION, keep it
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
        setState(STATE_NORMAL)
    }

    override fun onSearchEditTextBackPressed() {
        onBackPressed()
    }

    @SuppressLint("RtlHardcoded")
    override fun onStartDragHandler() {
        // Lock right drawer
        setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT)
    }

    @SuppressLint("RtlHardcoded")
    override fun onEndDragHandler() {
        // Restore right drawer
        setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT)

        mSearchBarMover?.returnSearchBarPosition()
    }

    @SuppressLint("RtlHardcoded")
    override fun onStateChange(searchBar: SearchBar, newState: Int, oldState: Int, animation: Boolean) {
        if (mLeftDrawable == null || mRightDrawable == null) return

        when (oldState) {
            SearchBar.STATE_NORMAL -> {
                mLeftDrawable!!.setArrow(if (animation) ANIMATE_TIME else 0)
                mRightDrawable!!.setDelete(if (animation) ANIMATE_TIME else 0)
            }
            SearchBar.STATE_SEARCH -> {
                if (newState == SearchBar.STATE_NORMAL) {
                    mLeftDrawable!!.setMenu(if (animation) ANIMATE_TIME else 0)
                    mRightDrawable!!.setAdd(if (animation) ANIMATE_TIME else 0)
                }
            }
            SearchBar.STATE_SEARCH_LIST -> {
                if (newState == STATE_NORMAL) {
                    mLeftDrawable!!.setMenu(if (animation) ANIMATE_TIME else 0)
                    mRightDrawable!!.setAdd(if (animation) ANIMATE_TIME else 0)
                }
            }
        }

        if (newState == STATE_NORMAL || newState == STATE_SIMPLE_SEARCH) {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.LEFT)
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT)
        } else {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT)
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT)
        }
    }

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
        // Auto-refresh gallery when sort options change
        mHelper?.refresh()
    }

    // SearchBarMover.Helper
    override fun isValidView(recyclerView: RecyclerView): Boolean {
        return (mState == STATE_NORMAL && recyclerView === mRecyclerView) ||
                (mState == STATE_SEARCH && recyclerView === mSearchLayout)
    }

    // SearchBarMover.Helper
    override fun getValidRecyclerView(): RecyclerView? {
        return if (mState == STATE_NORMAL || mState == STATE_SIMPLE_SEARCH) {
            mRecyclerView
        } else {
            mSearchLayout
        }
    }

    // SearchBarMover.Helper
    override fun forceShowSearchBar(): Boolean {
        return mState == STATE_SIMPLE_SEARCH || mState == STATE_SEARCH_SHOW_LIST
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

    private fun onGetGalleryListSuccess(result: GalleryListParser.Result, taskId: Int) {
        if (mHelper != null && mSearchBarMover != null &&
            mHelper!!.isCurrentTask(taskId)
        ) {
            val emptyString: String = if (result.customErrorString == null) {
                resources2!!.getString(
                    if (mUrlBuilder!!.mode == ListUrlBuilder.MODE_SUBSCRIPTION && result.noWatchedTags)
                        R.string.gallery_list_empty_hit_subscription
                    else R.string.gallery_list_empty_hit
                )
            } else {
                result.customErrorString
            }

            mHelper!!.setEmptyString(emptyString)
            mHelper!!.onGetPageData(taskId, result, result.galleryInfoList)
        }
    }

    /**
     * LANraragi: handle search result from LRRArchivePagingSource.
     */
    private fun onGetPagingSourceSuccess(
        data: List<GalleryInfo>, taskId: Int, page: Int, hasMore: Boolean
    ) {
        if (mHelper != null && mSearchBarMover != null &&
            mHelper!!.isCurrentTask(taskId)
        ) {
            mHelper!!.setEmptyString(getString(R.string.gallery_list_empty_hit))

            // Approximate totalPages/nextPage for ContentHelper.
            // Exact total is unavailable from PagingSource alone.
            val totalPages = if (hasMore) page + 2 else page + 1
            val nextPage = if (hasMore) page + 1 else 0
            mHelper!!.onGetPageData(taskId, totalPages, nextPage, data)
        }
    }

    private fun onGetGalleryListFailure(e: Exception, taskId: Int) {
        if (mHelper != null && mSearchBarMover != null &&
            mHelper!!.isCurrentTask(taskId)
        ) {
            mHelper!!.onGetException(taskId, e)
        }
    }

    // URL suggestion classes (UrlSuggestion, GalleryDetailUrlSuggestion,
    // GalleryPageUrlSuggestion) moved to GallerySearchHelper.

    private inner class GalleryListAdapter(
        inflater: LayoutInflater,
        resources: Resources, recyclerView: RecyclerView, type: Int
    ) : GalleryAdapterNew(inflater, resources, recyclerView, type, true, executorService, showReadProgress) {

        override fun getItemCount(): Int {
            return mHelper?.size() ?: 0
        }

        override fun getDataAt(position: Int): GalleryInfo? {
            return mHelper?.getDataAtEx(position)
        }
    }

    inner class GalleryListHelper : GalleryInfoContentHelper() {

        // Default page size for LANraragi pagination.
        // This should match the server's archives_per_page setting.
        override fun getPageData(taskId: Int, type: Int, page: Int) {
            // LANraragi: fetch archives via LRRArchivePagingSource
            val serverUrl = LRRAuthManager.getServerUrl()
            if (serverUrl.isNullOrEmpty()) return

            // Build search filter from mUrlBuilder keyword
            var filter: String? = null
            var categoryId: String? = null
            if (mUrlBuilder != null) {
                val keyword = mUrlBuilder!!.keyword
                if (!keyword.isNullOrEmpty()) {
                    if (keyword.startsWith("category:")) {
                        // Category filter from LRRCategoriesScene
                        categoryId = keyword.substring("category:".length)
                    } else {
                        filter = keyword
                    }
                }
            }

            val currentPage = page

            // Get sort options from search layout
            val sortBy = mSearchLayout?.sortBy ?: "date_added"
            val sortOrder = mSearchLayout?.sortOrder ?: "desc"

            // Use LRRArchivePagingSource as a direct data fetcher.
            // This reuses the same PagingSource that backs GalleryListViewModel,
            // keeping the existing ContentHelper pagination framework intact.
            val pagingSource = LRRArchivePagingSource(
                client = LRRClientProvider.getClient(),
                baseUrl = LRRClientProvider.getBaseUrl(),
                filter = filter,
                category = categoryId,
                sortby = sortBy,
                order = sortOrder
            )

            ServiceRegistry.coroutineModule.ioScope.launch {
                try {
                    val loadResult = pagingSource.load(
                        PagingSource.LoadParams.Refresh(
                            key = currentPage,
                            loadSize = LRR_PAGE_SIZE,
                            placeholdersEnabled = false
                        )
                    )
                    when (loadResult) {
                        is PagingSource.LoadResult.Page -> {
                            val hasMore = loadResult.nextKey != null
                            withContext(Dispatchers.Main) {
                                onGetPagingSourceSuccess(
                                    loadResult.data, taskId, currentPage, hasMore
                                )
                            }
                        }
                        is PagingSource.LoadResult.Error -> {
                            withContext(Dispatchers.Main) {
                                onGetGalleryListFailure(
                                    loadResult.throwable as? Exception
                                        ?: Exception(loadResult.throwable),
                                    taskId
                                )
                            }
                        }
                        is PagingSource.LoadResult.Invalid -> {
                            withContext(Dispatchers.Main) {
                                onGetGalleryListFailure(
                                    Exception("PagingSource invalidated"),
                                    taskId
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "LRR paging search failed", e)
                    withContext(Dispatchers.Main) {
                        onGetGalleryListFailure(e, taskId)
                    }
                }
            }
        }

        override fun getPageData(taskId: Int, type: Int, page: Int, append: String) {
            // Delegate to regular getPageData
            getPageData(taskId, type, page)
        }

        override fun getExPageData(pageAction: Int, taskId: Int, page: Int) {
            // LANraragi doesn't have href-based pagination,
            // just use regular page-based fetching
            getPageData(taskId, 0, page)
        }

        override fun getContext(): Context? {
            return this@GalleryListScene.ehContext
        }

        @SuppressLint("NotifyDataSetChanged")
        override fun notifyDataSetChanged() {
            mAdapter?.notifyDataSetChanged()
        }

        override fun notifyItemRangeRemoved(positionStart: Int, itemCount: Int) {
            mAdapter?.notifyItemRangeRemoved(positionStart, itemCount)
        }

        override fun notifyItemRangeInserted(positionStart: Int, itemCount: Int) {
            mAdapter?.notifyItemRangeInserted(positionStart, itemCount)
        }

        override fun onShowView(hiddenView: View, shownView: View) {
            mSearchBarMover?.showSearchBar()
            showActionFab()
        }

        override fun isDuplicate(d1: GalleryInfo, d2: GalleryInfo): Boolean {
            return d1.gid == d2.gid
        }

        override fun onScrollToPosition(postion: Int) {
            if (0 == postion) {
                mSearchBarMover?.showSearchBar()
                showActionFab()
            }
        }
    }

    private class GetGalleryListListener(
        context: Context, stageId: Int, sceneTag: String?, private val mTaskId: Int
    ) : EhCallback<GalleryListScene, GalleryListParser.Result>(context, stageId, sceneTag) {

        override fun onSuccess(result: GalleryListParser.Result) {
            scene?.onGetGalleryListSuccess(result, mTaskId)
        }

        override fun onFailure(e: Exception) {
            scene?.onGetGalleryListFailure(e, mTaskId)
        }

        override fun onCancel() {}

        override fun isInstance(scene: SceneFragment?): Boolean {
            return scene is GalleryListScene
        }
    }

    private class AddToFavoriteListener(
        context: Context, stageId: Int, sceneTag: String?
    ) : EhCallback<GalleryListScene, Void?>(context, stageId, sceneTag) {

        override fun onSuccess(result: Void?) {
            showTip(R.string.add_to_favorite_success, LENGTH_SHORT)
        }

        override fun onFailure(e: Exception) {
            showTip(R.string.add_to_favorite_failure, LENGTH_LONG)
        }

        override fun onCancel() {}

        override fun isInstance(scene: SceneFragment?): Boolean {
            return scene is GalleryListScene
        }
    }

    private class RemoveFromFavoriteListener(
        context: Context, stageId: Int, sceneTag: String?
    ) : EhCallback<GalleryListScene, Void?>(context, stageId, sceneTag) {

        override fun onSuccess(result: Void?) {
            showTip(R.string.remove_from_favorite_success, LENGTH_SHORT)
        }

        override fun onFailure(e: Exception) {
            showTip(R.string.remove_from_favorite_failure, LENGTH_LONG)
        }

        override fun onCancel() {}

        override fun isInstance(scene: SceneFragment?): Boolean {
            return scene is GalleryListScene
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

        const val STATE_NORMAL = 0
        const val STATE_SIMPLE_SEARCH = 1
        const val STATE_SEARCH = 2
        const val STATE_SEARCH_SHOW_LIST = 3

        private const val ANIMATE_TIME = 300L

        // Default page size for LANraragi pagination.
        // This should match the server's archives_per_page setting.
        private const val LRR_PAGE_SIZE = 100

        @JvmStatic
        fun startScene(scene: SceneFragment, lub: ListUrlBuilder) {
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
