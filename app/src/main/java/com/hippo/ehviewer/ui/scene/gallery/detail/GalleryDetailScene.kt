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

package com.hippo.ehviewer.ui.scene.gallery.detail

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.TextView
import com.hippo.android.resource.AttrResources
import com.hippo.drawerlayout.DrawerLayout
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.data.LRRArchive
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.widget.ArchiverDownloadProgress
import com.hippo.lib.yorozuya.AssertUtils
import com.hippo.lib.yorozuya.IntIdGenerator
import com.hippo.lib.yorozuya.ViewUtils
import com.hippo.ripple.Ripple
import com.hippo.util.DrawableManager
import com.hippo.util.ExceptionUtils
import kotlinx.coroutines.launch
import com.hippo.view.ViewTransition
import com.hippo.widget.LoadImageView
import java.text.SimpleDateFormat
import java.util.Date

class GalleryDetailScene : BaseScene(), View.OnClickListener,
    View.OnLongClickListener {

    /*---------------
     View life cycle
     ---------------*/
    private var mTip: TextView? = null
    private var mViewTransition: ViewTransition? = null

    // Header
    private var mHeader: View? = null
    private var mActionGroup: ViewGroup? = null
    private var mRead: View? = null

    // Below header
    private var mBelowHeader: View? = null

    // Actions
    private var mActions: View? = null
    private var mHeartGroup: View? = null

    // Tags
    private var mEditTagsBtn: android.widget.ImageButton? = null

    // Progress
    private var mProgress: View? = null
    private var mViewTransition2: ViewTransition? = null

    private lateinit var viewModel: GalleryDetailViewModel

    /*---------------
     Extracted helpers
     ---------------*/
    private var mHeaderBinder: DetailHeaderBinder? = null
    private var mActionHandler: DetailActionHandler? = null

    /** Shortcut delegating to [GalleryDetailViewModel.action]. */
    private var mAction: String?
        get() = viewModel.action.value
        set(value) { viewModel.setAction(value) }

    /** Shortcut delegating to [GalleryDetailViewModel.galleryInfo]. */
    private var mGalleryInfo: GalleryInfo?
        get() = viewModel.galleryInfo.value
        set(value) { viewModel.setGalleryInfo(value) }

    /** Shortcut delegating to [GalleryDetailViewModel.downloadInfo]. */
    private var mDownloadInfo: DownloadInfo?
        get() = viewModel.downloadInfo.value
        set(value) { viewModel.setDownloadInfo(value) }

    /** Shortcut delegating to [GalleryDetailViewModel.gid]. */
    private var mGid: Long
        get() = viewModel.gid.value
        set(value) { viewModel.setGid(value) }

    /** Shortcut delegating to [GalleryDetailViewModel.token]. */
    private var mToken: String?
        get() = viewModel.token.value
        set(value) { viewModel.setToken(value) }

    /** Shortcut delegating to [GalleryDetailViewModel.galleryDetail]. */
    private var mGalleryDetail: GalleryDetail?
        get() = viewModel.galleryDetail.value
        set(value) { viewModel.setGalleryDetail(value) }

    private var mRequestId: Int = IntIdGenerator.INVALID_ID

    private var properties: MutableMap<String, String>? = null

    /** Shortcut delegating to [GalleryDetailViewModel.state]. */
    private var mState: Int
        get() = viewModel.state.value
        set(value) { viewModel.setState(value) }


    private var mContext: Context? = null
    private var activity: MainActivity? = null


    private fun handleArgs(args: Bundle?) {
        if (args == null) {
            return
        }

        // The ViewModel is Activity-scoped, so the previous gallery's
        // _galleryDetail / _downloadInfo / state would otherwise shadow the
        // arguments we are about to write via the detail > info > args
        // fallback in viewModel.getEffective*().
        viewModel.resetForNewEntry()

        val action = args.getString(KEY_ACTION)
        mAction = action
        if (ACTION_GALLERY_INFO == action) {
            val gi: GalleryInfo? = args.getParcelable(KEY_GALLERY_INFO)
            mGalleryInfo = gi
            // Add history
            if (gi != null) {
                viewModel.recordHistory(gi)
            }
        } else if (ACTION_GID_TOKEN == action) {
            mGid = args.getLong(KEY_GID)
            mToken = args.getString(KEY_TOKEN)
        } else if (ACTION_DOWNLOAD_GALLERY_INFO == action) {
            try {
                val di: DownloadInfo? = args.getParcelable(KEY_GALLERY_INFO)
                mDownloadInfo = di
                mGalleryInfo = di
                if (di != null) {
                    viewModel.recordHistory(di)
                }
            } catch (e: ClassCastException) {
                val gi: GalleryInfo? = args.getParcelable(KEY_GALLERY_INFO)
                mGalleryInfo = gi
                if (gi != null) {
                    viewModel.recordHistory(gi)
                }
            }
        }
    }

    // -1 for error
    private fun getGid(): Long = viewModel.getEffectiveGid()

    private fun getGalleryInfo(): GalleryInfo? = viewModel.getEffectiveGalleryInfo()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[GalleryDetailViewModel::class.java]

        if (savedInstanceState == null) {
            onInit()
        } else {
            onRestore(savedInstanceState)
        }

        val gi = mGalleryInfo
        if (properties == null && gi != null) {
            val date = Date()
            @SuppressLint("SimpleDateFormat")
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            properties = HashMap<String, String>().apply {
                put("Title", gi.title.orEmpty())
                put("Time", dateFormat.format(date))
            }
        }
    }

    private fun onInit() {
        handleArgs(arguments)
    }

    private fun onRestore(savedInstanceState: Bundle) {
        mAction = savedInstanceState.getString(KEY_ACTION)
        mGalleryInfo = savedInstanceState.getParcelable(KEY_GALLERY_INFO)
        mGid = savedInstanceState.getLong(KEY_GID)
        mToken = savedInstanceState.getString(KEY_TOKEN)
        mGalleryDetail = savedInstanceState.getParcelable(KEY_GALLERY_DETAIL)
        mRequestId = savedInstanceState.getInt(KEY_REQUEST_ID)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (mAction != null) {
            outState.putString(KEY_ACTION, mAction)
        }
        if (mGalleryInfo != null) {
            outState.putParcelable(KEY_GALLERY_INFO, mGalleryInfo)
        }
        outState.putLong(KEY_GID, mGid)
        if (mToken != null) {
            outState.putString(KEY_TOKEN, mToken)
        }
        if (mGalleryDetail != null) {
            outState.putParcelable(KEY_GALLERY_DETAIL, mGalleryDetail)
        }
        outState.putInt(KEY_REQUEST_ID, mRequestId)
    }

    override fun onCreateView2(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val context = getEHContext()
        // Get download state
        val gid = getGid()
        viewModel.initDownloadState(gid)

        val view = inflater.inflate(R.layout.scene_gallery_detail, container, false)

        val main = ViewUtils.`$$`(view, R.id.main) as ViewGroup
        val mainView = ViewUtils.`$$`(main, R.id.scroll_view)
        val progressView = ViewUtils.`$$`(main, R.id.progress_view)
        mTip = ViewUtils.`$$`(main, R.id.tip) as TextView
        mViewTransition = ViewTransition(mainView, progressView, mTip)

        assert(context != null)
        AssertUtils.assertNotNull(context)

        val actionsScrollView = ViewUtils.`$$`(view, R.id.actions_scroll_view)
        setDrawerGestureBlocker(object : DrawerLayout.GestureBlocker {
            private fun transformPointToViewLocal(point: IntArray, child: View) {
                var currentChild = child
                var viewParent: ViewParent? = currentChild.parent

                while (viewParent is View) {
                    val parentView = viewParent
                    point[0] += parentView.scrollX - currentChild.left
                    point[1] += parentView.scrollY - currentChild.top

                    if (parentView is DrawerLayout) {
                        break
                    }

                    currentChild = parentView
                    viewParent = currentChild.parent
                }
            }

            override fun shouldBlockGesture(ev: MotionEvent): Boolean {
                val point = intArrayOf(ev.x.toInt(), ev.y.toInt())
                transformPointToViewLocal(point, actionsScrollView)
                return !isDrawersVisible() &&
                    point[0] > 0 && point[0] < actionsScrollView.width &&
                    point[1] > 0 && point[1] < actionsScrollView.height
            }
        })

        val nonNullContext = requireContext()
        val drawable = DrawableManager.getVectorDrawable(nonNullContext, R.drawable.big_sad_pandroid)
        drawable?.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        mTip!!.setCompoundDrawables(null, drawable, null, null)
        mTip!!.setOnClickListener(this)

        mBelowHeader = mainView.findViewById(R.id.below_header)
        val belowHeader = mBelowHeader!!
        val isDarkTheme = !AttrResources.getAttrBoolean(nonNullContext, androidx.appcompat.R.attr.isLightTheme)
        mHeader = ViewUtils.`$$`(belowHeader, R.id.header)
        val colorBg = ViewUtils.`$$`(mHeader, R.id.color_bg)
        val thumb = ViewUtils.`$$`(mHeader, R.id.thumb) as LoadImageView
        val title = ViewUtils.`$$`(mHeader, R.id.title) as TextView
        val uploader = ViewUtils.`$$`(mHeader, R.id.uploader) as TextView
        val otherActions = ViewUtils.`$$`(mHeader, R.id.other_actions) as ImageView
        mActionGroup = ViewUtils.`$$`(mHeader, R.id.action_card) as ViewGroup
        val download = ViewUtils.`$$`(mActionGroup, R.id.download) as TextView
        val archiverDownloadProgress = ViewUtils.`$$`(mHeader, R.id.archiver_download_progress) as ArchiverDownloadProgress
        mRead = ViewUtils.`$$`(mActionGroup, R.id.read)
        Ripple.addRipple(otherActions, isDarkTheme)
        Ripple.addRipple(download, isDarkTheme)
        Ripple.addRipple(mRead!!, isDarkTheme)
        uploader.setOnClickListener(this)
        otherActions.setOnClickListener(this)
        download.setOnClickListener(this)
        download.setOnLongClickListener(this)
        mRead!!.setOnClickListener(this)
        title.setOnClickListener(this)

        uploader.setOnLongClickListener(this)

        val infoView = ViewUtils.`$$`(belowHeader, R.id.info)
        val pages = ViewUtils.`$$`(infoView, R.id.pages) as TextView
        val size = ViewUtils.`$$`(infoView, R.id.size) as TextView

        mActions = ViewUtils.`$$`(belowHeader, R.id.actions)
        val ratingText = ViewUtils.`$$`(mActions, R.id.rating_text) as TextView
        val rating = ViewUtils.`$$`(mActions, R.id.rating) as RatingBar
        mHeartGroup = ViewUtils.`$$`(mActions, R.id.heart_group)
        val heart = ViewUtils.`$$`(mHeartGroup, R.id.heart) as TextView
        val heartOutline = ViewUtils.`$$`(mHeartGroup, R.id.heart_outline) as TextView
        Ripple.addRipple(mHeartGroup!!, isDarkTheme)
        mHeartGroup!!.setOnClickListener(this)
        mHeartGroup!!.setOnLongClickListener(this)

        val tags = ViewUtils.`$$`(belowHeader, R.id.tags) as LinearLayout
        val noTags = ViewUtils.`$$`(tags, R.id.no_tags) as TextView

        // Initialize helpers
        mHeaderBinder = DetailHeaderBinder(
            viewModel, viewLifecycleOwner,
            thumb, title, uploader, pages, size,
            ratingText, rating, heart, heartOutline,
            archiverDownloadProgress, colorBg, tags, noTags
        )
        mHeaderBinder!!.ensureActionDrawable(nonNullContext)

        mActionHandler = DetailActionHandler(this, viewModel, viewLifecycleOwner)
        mActionHandler!!.otherActions = otherActions
        mActionHandler!!.download = download
        mActionHandler!!.onFavoriteChanged = { gd ->
            mHeaderBinder?.updateFavoriteDrawable(gd)
        }

        // Make rating bar interactive: touch/drag to rate, release to confirm
        rating.setIsIndicator(false)
        rating.onRatingBarChangeListener =
            RatingBar.OnRatingBarChangeListener { _, ratingValue, fromUser ->
                if (!fromUser || mGalleryDetail == null) return@OnRatingBarChangeListener
                val gd = mGalleryDetail!!
                val arcid = gd.token ?: return@OnRatingBarChangeListener

                // Update local UI immediately
                gd.rating = ratingValue
                gd.rated = true
                ratingText.text = LRRArchive.buildRatingEmoji(Math.round(ratingValue))

                // Write to LANraragi server
                RatingHelper.saveRatingToServer(arcid, ratingValue, null)
            }

        mEditTagsBtn = ViewUtils.`$$`(tags, R.id.edit_tags_btn) as? android.widget.ImageButton
        mEditTagsBtn?.let { btn ->
            val isLrr = LRRAuthManager.getServerUrl() != null
            btn.visibility = if (isLrr) View.VISIBLE else View.GONE
            btn.setOnClickListener {
                if (mGalleryDetail != null) {
                    TagEditDialog.show(
                        activity2, mGalleryDetail!!.token,
                        mGalleryDetail!!.tags
                    ) {
                        if (mState != STATE_REFRESH && mState != STATE_REFRESH_HEADER) {
                            adjustViewVisibility(STATE_REFRESH, true)
                            request()
                        }
                    }
                }
            }
        }

        mProgress = ViewUtils.`$$`(mainView, R.id.progress)

        mViewTransition2 = ViewTransition(mBelowHeader, mProgress)

        if (prepareData()) {
            if (mGalleryDetail != null) {
                bindViewSecond()
                mHeaderBinder?.setTransitionName(getGid())
                adjustViewVisibility(STATE_NORMAL, false)
            } else if (mGalleryInfo != null) {
                bindViewFirst()
                mHeaderBinder?.setTransitionName(getGid())
                adjustViewVisibility(STATE_REFRESH_HEADER, false)
            } else {
                adjustViewVisibility(STATE_REFRESH, false)
            }
        } else {
            mTip!!.setText(R.string.error_cannot_find_gallery)
            adjustViewVisibility(STATE_FAILED, false)
        }

        viewModel.downloadManager.addDownloadInfoListener(viewModel.downloadInfoListener)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Observe gallery detail updates from ViewModel (replaces RequestHelper callback)
        lifecycleScope.launch {
            viewModel.galleryDetail.collect { detail ->
                if (detail != null && mState != STATE_NORMAL) {
                    onGetGalleryDetailSuccess(detail)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.detailError.collect { e ->
                onGetGalleryDetailFailure(e)
            }
        }
        // Observe download state changes from ViewModel (replaces DownloadHelper listener)
        lifecycleScope.launch {
            viewModel.downloadState.collect {
                mActionHandler?.updateDownloadText()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Sync local reading progress back to the in-memory model
        val info = getGalleryInfo() ?: return
        val localPage = GalleryProvider2.loadReadingProgress(requireContext(), info.gid) + 1
        if (localPage > info.progress) {
            info.progress = localPage
        }
        mHeaderBinder?.bindReadProgress(info)
    }

    override fun onDestroyView() {
        super.onDestroyView()

        val context = getEHContext()
        AssertUtils.assertNotNull(context)
        viewModel.downloadManager.removeDownloadInfoListener(viewModel.downloadInfoListener)
        GalleryTagHelper.destroy()

        setDrawerGestureBlocker(null)

        mTip = null
        mViewTransition = null

        mHeader = null
        mActionGroup = null
        mRead = null
        mBelowHeader = null

        mActions = null
        mHeartGroup = null
        mEditTagsBtn = null

        mProgress = null
        mViewTransition2 = null

        mHeaderBinder = null
        mActionHandler?.destroy()
        mActionHandler = null

        properties = null
    }

    private fun prepareData(): Boolean {
        val context = getEHContext()
        AssertUtils.assertNotNull(context)

        // Try ViewModel cache (checks in-memory detail, then galleryDetailCache)
        if (!viewModel.tryLoadFromCache()) {
            return false
        }
        if (mGalleryDetail != null) {
            return true
        }

        val application = requireContext().applicationContext as EhApplication
        if (application.containGlobalStuff(mRequestId)) {
            // request exist
            return true
        }

        // Do request
        return request()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun request(url: String, resultMode: Int): Boolean {
        // LANraragi: ignore E-Hentai URL, fetch archive metadata via LRRArchiveApi
        return request()
    }

    private fun request(): Boolean {
        getEHContext() ?: return false
        return viewModel.requestGalleryDetail(
            getString(R.string.lrr_category_info_suffix),
            getString(R.string.lrr_category_count_suffix)
        )
    }

    /**
     * Called by [DetailActionHandler] to trigger a refresh from the popup menu.
     */
    internal fun requestRefresh() {
        if (mState != STATE_REFRESH && mState != STATE_REFRESH_HEADER) {
            adjustViewVisibility(STATE_REFRESH, true)
            request()
        }
    }

    private fun adjustViewVisibility(state: Int, animation: Boolean) {
        if (state == mState) {
            return
        }
        if (mViewTransition == null || mViewTransition2 == null) {
            return
        }

        val oldState = mState
        mState = state

        @Suppress("NAME_SHADOWING")
        val animation = !TRANSITION_ANIMATION_DISABLED && animation

        when (state) {
            STATE_NORMAL -> {
                // Show mMainView
                mViewTransition!!.showView(0, animation)
                // Show mBelowHeader
                mViewTransition2!!.showView(0, animation)
            }
            STATE_REFRESH -> {
                // Show mProgressView
                mViewTransition!!.showView(1, animation)
            }
            STATE_REFRESH_HEADER -> {
                // Show mMainView
                mViewTransition!!.showView(0, animation)
                // Show mProgress
                mViewTransition2!!.showView(1, animation)
            }
            else -> {
                // STATE_INIT, STATE_FAILED
                // Show mFailedView
                mViewTransition!!.showView(2, animation)
            }
        }
        val context = getEHContext() ?: return
        if ((oldState == STATE_INIT || oldState == STATE_FAILED || oldState == STATE_REFRESH) &&
            (state == STATE_NORMAL || state == STATE_REFRESH_HEADER) &&
            AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme)
        ) {
            mHeaderBinder?.createCircularRevealOrPost()
        }
    }

    private fun bindViewFirst() {
        if (mGalleryDetail != null) return
        val binder = mHeaderBinder ?: return
        binder.bindViewFirst(mAction, mGalleryInfo)
        mActionHandler?.updateDownloadText()
    }

    private fun bindViewSecond() {
        try {
            val gd = mGalleryDetail ?: return
            val binder = mHeaderBinder ?: return
            binder.bindViewSecond(gd, mGalleryInfo, getEHContext(), layoutInflater2, this, this)
            mActionHandler?.updateDownloadText()
        } catch (e: Exception) {
            android.util.Log.e("GalleryDetailScene", "bindViewSecond crashed", e)
        }
    }

    fun bindArchiverProgress(gd: GalleryDetail) {
        mHeaderBinder?.bindArchiverProgress(gd)
    }

    override fun onClick(v: View) {
        mContext = getEHContext()
        activity = activity2
        if (mContext == null || activity == null) {
            return
        }

        if (mTip === v) {
            if (request()) {
                adjustViewVisibility(STATE_REFRESH, true)
            }
        } else {
            mActionHandler?.onClick(v, mContext!!, activity)
        }
    }

    override fun onLongClick(v: View): Boolean {
        mContext = getEHContext()
        activity = activity2
        if (activity == null) {
            return false
        }

        return mActionHandler?.onLongClick(v, mContext ?: return false, activity) ?: false
    }


    override fun onBackPressed() {
        finish()
    }


    internal fun onGetGalleryDetailSuccess(result: GalleryDetail) {
        try {
            onGetGalleryDetailSuccessInternal(result)
        } catch (e: Exception) {
            android.util.Log.e("GalleryDetailScene", "onGetGalleryDetailSuccess crashed", e)
        }
    }

    private fun onGetGalleryDetailSuccessInternal(result: GalleryDetail) {
        mGalleryDetail = result
        viewModel.refreshDownloadState()
        val dlState = viewModel.downloadState.value
        if (dlState != DownloadInfo.STATE_INVALID) {
            val di = mDownloadInfo
            if (di != null && di.thumb != null &&
                di.thumb != result.thumb && di.gid == result.gid
            ) {
                mHeaderBinder?.useNetWorkLoadThumb = true
                di.updateInfo(result)
                di.state = dlState
                viewModel.persistDownloadInfo(di)
            }
        }
        adjustViewVisibility(STATE_NORMAL, true)
        bindViewSecond()
    }

    internal fun onGetGalleryDetailFailure(e: Exception) {
        e.printStackTrace()
        val context = getEHContext()
        if (context != null && mTip != null) {
            val error = ExceptionUtils.getReadableString(e)
            mTip!!.text = error
            adjustViewVisibility(STATE_FAILED, true)
        }
    }

    internal fun onGetGalleryDetailUpdateFailure(e: Exception) {
        Analytics.recordException(e)
        adjustViewVisibility(STATE_NORMAL, true)
    }

    companion object {
        private const val REQUEST_CODE_COMMENT_GALLERY = 0

        private const val STATE_INIT = GalleryDetailViewModel.STATE_INIT
        private const val STATE_NORMAL = GalleryDetailViewModel.STATE_NORMAL
        private const val STATE_REFRESH = GalleryDetailViewModel.STATE_REFRESH
        private const val STATE_REFRESH_HEADER = GalleryDetailViewModel.STATE_REFRESH_HEADER
        private const val STATE_FAILED = GalleryDetailViewModel.STATE_FAILED

        const val KEY_ACTION = "action"
        const val ACTION_GALLERY_INFO = "action_gallery_info"
        const val ACTION_DOWNLOAD_GALLERY_INFO = "action_download_gallery_info"
        const val ACTION_GID_TOKEN = "action_gid_token"

        const val KEY_GALLERY_INFO = "gallery_info"
        const val KEY_GID = "gid"
        const val KEY_TOKEN = "token"
        const val KEY_PAGE = "page"

        private const val KEY_GALLERY_DETAIL = "gallery_detail"
        private const val KEY_REQUEST_ID = "request_id"

        private const val TRANSITION_ANIMATION_DISABLED = true
    }
}
