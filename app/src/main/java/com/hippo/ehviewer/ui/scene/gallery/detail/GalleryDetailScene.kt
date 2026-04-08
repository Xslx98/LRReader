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
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import com.hippo.android.resource.AttrResources
import com.hippo.drawerlayout.DrawerLayout
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.UrlOpener
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.hippo.ehviewer.client.EhCacheKeyFactory
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.client.lrr.LRRAuthManager
import com.hippo.ehviewer.client.lrr.data.LRRArchive
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.ehviewer.ui.GalleryOpenHelper
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.ui.scene.TransitionNameFactory
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene
import com.hippo.ehviewer.util.ClipboardUtil
import com.hippo.ehviewer.widget.ArchiverDownloadProgress
import com.hippo.lib.yorozuya.AssertUtils
import com.hippo.lib.yorozuya.IntIdGenerator
import com.hippo.lib.yorozuya.SimpleHandler
import com.hippo.lib.yorozuya.ViewUtils
import com.hippo.reveal.ViewAnimationUtils
import com.hippo.ripple.Ripple
import com.hippo.util.DrawableManager
import com.hippo.util.ExceptionUtils
import kotlinx.coroutines.launch
import com.hippo.view.ViewTransition
import com.hippo.widget.LoadImageView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ExecutorService

class GalleryDetailScene : BaseScene(), View.OnClickListener,
    View.OnLongClickListener {

    /*---------------
     View life cycle
     ---------------*/
    private var mTip: TextView? = null
    private var mViewTransition: ViewTransition? = null

    // Header
    private var mHeader: View? = null
    private var mColorBg: View? = null
    private var mThumb: LoadImageView? = null
    private var mTitle: TextView? = null
    private var mUploader: TextView? = null
    private var mOtherActions: ImageView? = null
    private var mActionGroup: ViewGroup? = null
    private var mDownload: TextView? = null
    private var mRead: View? = null

    // Below header
    private var mBelowHeader: View? = null

    // Info
    private var mPages: TextView? = null
    private var mSize: TextView? = null

    // Actions
    private var mActions: View? = null
    private var mRatingText: TextView? = null
    private var mRating: RatingBar? = null
    private var mHeartGroup: View? = null
    private var mHeart: TextView? = null
    private var mHeartOutline: TextView? = null

    // Tags
    private var mTags: LinearLayout? = null
    private var mNoTags: TextView? = null
    private var mEditTagsBtn: android.widget.ImageButton? = null

    // Progress
    private var mProgress: View? = null
    private var mArchiverDownloadProgress: ArchiverDownloadProgress? = null
    private var mViewTransition2: ViewTransition? = null
    private var mPopupMenu: PopupMenu? = null

    private lateinit var viewModel: GalleryDetailViewModel

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


    private var useNetWorkLoadThumb: Boolean = false

    private var mContext: Context? = null
    private var activity: MainActivity? = null

    private var executorService: ExecutorService? = null

    private val handler = Handler(Looper.getMainLooper())

    // Extracted helpers
    private val downloadHelperCallback = object : GalleryDownloadHelper.Callback {
        override fun getContext(): Context? = getEHContext()
        override fun getActivity(): MainActivity? = activity2
        override fun getGid(): Long = this@GalleryDetailScene.getGid()
        override fun getGalleryInfo(): GalleryInfo? = this@GalleryDetailScene.getGalleryInfo()
        override fun getDownloadView(): TextView? = mDownload
        override fun getString(resId: Int): String = this@GalleryDetailScene.getString(resId)
        override fun getString(resId: Int, vararg formatArgs: Any): String =
            this@GalleryDetailScene.getString(resId, *formatArgs)
    }

    private val tagHelperCallback = object : GalleryTagHelper.Callback {
        override fun getContext(): Context? = getEHContext()
        override fun getInflater(): LayoutInflater? = layoutInflater2
        override fun getTagsLayout(): LinearLayout? = mTags
        override fun getNoTagsView(): TextView? = mNoTags
        override fun getString(resId: Int): String = this@GalleryDetailScene.getString(resId)
        override fun getString(resId: Int, vararg formatArgs: Any): String =
            this@GalleryDetailScene.getString(resId, *formatArgs)
        override fun showTip(resId: Int, length: Int) =
            this@GalleryDetailScene.showTip(resId, length)
        override fun getUploader(): String? = this@GalleryDetailScene.getUploader()
        override fun getTagClickListener(): View.OnClickListener = this@GalleryDetailScene
        override fun getTagLongClickListener(): View.OnLongClickListener = this@GalleryDetailScene
    }

    private val requestHelperCallback = object : GalleryDetailRequestHelper.Callback {
        override fun getToken(): String? = this@GalleryDetailScene.getToken()
        override fun getActivity(): android.app.Activity? =
            this@GalleryDetailScene.getActivity()
        override fun getString(resId: Int): String = this@GalleryDetailScene.getString(resId)
        override fun onGetGalleryDetailSuccess(result: GalleryDetail) =
            this@GalleryDetailScene.onGetGalleryDetailSuccess(result)
        override fun onGetGalleryDetailFailure(e: Exception) =
            this@GalleryDetailScene.onGetGalleryDetailFailure(e)
    }

    private val mDownloadHelper = GalleryDownloadHelper(downloadHelperCallback)
    private val mTagHelper = GalleryTagHelper(tagHelperCallback)
    private val mRequestHelper = GalleryDetailRequestHelper(requestHelperCallback)

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

    private fun getGalleryDetailUrl(): String? {
        val gid = viewModel.getEffectiveGid()
        val token = viewModel.getEffectiveToken()
        if (gid == -1L) return null
        return EhUrl.getGalleryDetailUrl(gid, token, 0, false)
    }

    // -1 for error
    private fun getGid(): Long = viewModel.getEffectiveGid()

    private fun getToken(): String? = viewModel.getEffectiveToken()

    private fun getUploader(): String? = viewModel.getEffectiveUploader()

    // -1 for error
    private fun getCategory(): Int = viewModel.getEffectiveCategory()

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
        mDownloadHelper.initDownloadState(gid)

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
        mColorBg = ViewUtils.`$$`(mHeader, R.id.color_bg)
        mThumb = ViewUtils.`$$`(mHeader, R.id.thumb) as LoadImageView
        mTitle = ViewUtils.`$$`(mHeader, R.id.title) as TextView
        mUploader = ViewUtils.`$$`(mHeader, R.id.uploader) as TextView
        mOtherActions = ViewUtils.`$$`(mHeader, R.id.other_actions) as ImageView
        mActionGroup = ViewUtils.`$$`(mHeader, R.id.action_card) as ViewGroup
        mDownload = ViewUtils.`$$`(mActionGroup, R.id.download) as TextView
        mArchiverDownloadProgress = ViewUtils.`$$`(mHeader, R.id.archiver_download_progress) as ArchiverDownloadProgress
        mRead = ViewUtils.`$$`(mActionGroup, R.id.read)
        Ripple.addRipple(mOtherActions!!, isDarkTheme)
        Ripple.addRipple(mDownload!!, isDarkTheme)
        Ripple.addRipple(mRead!!, isDarkTheme)
        mUploader!!.setOnClickListener(this)
        mOtherActions!!.setOnClickListener(this)
        mDownload!!.setOnClickListener(this)
        mDownload!!.setOnLongClickListener(this)
        mRead!!.setOnClickListener(this)
        mTitle!!.setOnClickListener(this)

        mUploader!!.setOnLongClickListener(this)

        val infoView = ViewUtils.`$$`(belowHeader, R.id.info)
        mPages = ViewUtils.`$$`(infoView, R.id.pages) as TextView
        mSize = ViewUtils.`$$`(infoView, R.id.size) as TextView

        mActions = ViewUtils.`$$`(belowHeader, R.id.actions)
        mRatingText = ViewUtils.`$$`(mActions, R.id.rating_text) as TextView
        mRating = ViewUtils.`$$`(mActions, R.id.rating) as RatingBar
        mHeartGroup = ViewUtils.`$$`(mActions, R.id.heart_group)
        mHeart = ViewUtils.`$$`(mHeartGroup, R.id.heart) as TextView
        mHeartOutline = ViewUtils.`$$`(mHeartGroup, R.id.heart_outline) as TextView
        Ripple.addRipple(mHeartGroup!!, isDarkTheme)
        mHeartGroup!!.setOnClickListener(this)
        mHeartGroup!!.setOnLongClickListener(this)
        ensureActionDrawable(nonNullContext)

        // Make rating bar interactive: touch/drag to rate, release to confirm
        mRating!!.setIsIndicator(false)
        mRating!!.onRatingBarChangeListener =
            RatingBar.OnRatingBarChangeListener { _, rating, fromUser ->
                if (!fromUser || mGalleryDetail == null) return@OnRatingBarChangeListener
                val newRating = rating
                val gd = mGalleryDetail!!
                val arcid = gd.token ?: return@OnRatingBarChangeListener

                // Update local UI immediately
                gd.rating = newRating
                gd.rated = true
                mRatingText?.text = LRRArchive.buildRatingEmoji(Math.round(newRating))

                // Write to LANraragi server
                RatingHelper.saveRatingToServer(arcid, newRating, null)
            }

        mTags = ViewUtils.`$$`(belowHeader, R.id.tags) as LinearLayout
        mNoTags = ViewUtils.`$$`(mTags, R.id.no_tags) as TextView
        mEditTagsBtn = ViewUtils.`$$`(mTags, R.id.edit_tags_btn) as? android.widget.ImageButton
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
                setTransitionName()
                adjustViewVisibility(STATE_NORMAL, false)
            } else if (mGalleryInfo != null) {
                bindViewFirst()
                setTransitionName()
                adjustViewVisibility(STATE_REFRESH_HEADER, false)
            } else {
                adjustViewVisibility(STATE_REFRESH, false)
            }
        } else {
            mTip!!.setText(R.string.error_cannot_find_gallery)
            adjustViewVisibility(STATE_FAILED, false)
        }

        viewModel.downloadManager.addDownloadInfoListener(mDownloadHelper)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onDestroyView() {
        super.onDestroyView()

        val context = getEHContext()
        AssertUtils.assertNotNull(context)
        viewModel.downloadManager.removeDownloadInfoListener(mDownloadHelper)
        mTagHelper.destroy()

        setDrawerGestureBlocker(null)

        mTip = null
        mViewTransition = null

        mHeader = null
        mColorBg = null
        mThumb = null
        mTitle = null
        mUploader = null
        mOtherActions = null
        mActionGroup = null
        mDownload = null

        mRead = null
        mBelowHeader = null
        mArchiverDownloadProgress = null

        mPages = null
        mSize = null

        mActions = null
        mRatingText = null
        mRating = null
        mHeartGroup = null
        mHeart = null
        mHeartOutline = null

        mTags = null
        mNoTags = null
        mEditTagsBtn = null

        mProgress = null

        mViewTransition2 = null

        mPopupMenu = null

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
        return mRequestHelper.request()
    }

    private fun setActionDrawable(text: TextView, drawable: Drawable) {
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        text.setCompoundDrawables(null, drawable, null, null)
    }

    private fun ensureActionDrawable(context: Context) {
        val heart = DrawableManager.getVectorDrawable(context, R.drawable.v_heart_primary_x48)
        if (heart != null) {
            mHeart?.let { setActionDrawable(it, heart) }
        }
        val heartOutline = DrawableManager.getVectorDrawable(context, R.drawable.v_heart_outline_primary_x48)
        if (heartOutline != null) {
            mHeartOutline?.let { setActionDrawable(it, heartOutline) }
        }
    }

    private fun createCircularReveal(): Boolean {
        val colorBg = mColorBg ?: return false

        val w = colorBg.width
        val h = colorBg.height
        if (ViewCompat.isAttachedToWindow(colorBg) && w != 0 && h != 0) {
            val context = getEHContext() ?: return false
            val resources = context.resources
            val keylineMargin = resources.getDimensionPixelSize(R.dimen.keyline_margin)
            val thumbWidth = resources.getDimensionPixelSize(R.dimen.gallery_detail_thumb_width)
            val thumbHeight = resources.getDimensionPixelSize(R.dimen.gallery_detail_thumb_height)

            val x = thumbWidth / 2 + keylineMargin
            val y = thumbHeight / 2 + keylineMargin

            val radiusX = maxOf(Math.abs(x), Math.abs(w - x))
            val radiusY = maxOf(Math.abs(y), Math.abs(h - y))
            val radius = Math.hypot(radiusX.toDouble(), radiusY.toDouble()).toFloat()

            ViewAnimationUtils.createCircularReveal(colorBg, x, y, 0f, radius)
                .setDuration(300).start()
            return true
        } else {
            return false
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
            if (!createCircularReveal()) {
                SimpleHandler.getInstance().post { createCircularReveal() }
            }
        }
    }

    private fun bindViewFirst() {
        if (mGalleryDetail != null) {
            return
        }
        if (mThumb == null || mTitle == null || mUploader == null) {
            return
        }

        if ((ACTION_GALLERY_INFO == mAction || ACTION_DOWNLOAD_GALLERY_INFO == mAction) && mGalleryInfo != null) {
            val gi = mGalleryInfo!!
            mThumb!!.load(EhCacheKeyFactory.getThumbKey(gi.gid), gi.thumb)
            mTitle!!.text = EhUtils.getSuitableTitle(gi)
            mUploader!!.text = gi.uploader
            mDownloadHelper.updateDownloadText()
        }
    }

    private fun updateFavoriteDrawable() {
        val gd = mGalleryDetail ?: return
        if (mHeart == null || mHeartOutline == null) {
            return
        }

        lifecycleScope.launch {
            val isFav = gd.isFavorited || viewModel.isLocalFavorite(gd.gid)
            val a = getActivity()
            a?.runOnUiThread {
                if (mHeart == null || mHeartOutline == null) return@runOnUiThread
                if (isFav) {
                    mHeart!!.visibility = View.VISIBLE
                    if (gd.favoriteName == null) {
                        mHeart!!.setText(R.string.local_favorites)
                    } else {
                        mHeart!!.text = gd.favoriteName
                    }
                    mHeartOutline!!.visibility = View.GONE
                } else {
                    mHeart!!.visibility = View.GONE
                    mHeartOutline!!.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun bindViewSecond() {
        try {
            bindViewSecondInternal()
        } catch (e: Exception) {
            android.util.Log.e("GalleryDetailScene", "bindViewSecond crashed", e)
        }
    }

    private fun bindViewSecondInternal() {
        val gd = mGalleryDetail ?: return
        if (mThumb == null || mTitle == null || mUploader == null ||
            mPages == null || mSize == null ||
            mRatingText == null || mRating == null
        ) {
            return
        }
        val resources = resources2
        if (mGalleryInfo == null) {
            mThumb!!.load(EhCacheKeyFactory.getThumbKey(gd.gid), gd.thumb)
        } else {
            if (useNetWorkLoadThumb) {
                mThumb!!.load(EhCacheKeyFactory.getThumbKey(gd.gid), gd.thumb)
                useNetWorkLoadThumb = false
            } else {
                mThumb!!.load(EhCacheKeyFactory.getThumbKey(gd.gid), gd.thumb, false)
            }
        }

        mTitle!!.text = EhUtils.getSuitableTitle(gd)
        mUploader!!.text = gd.uploader
        mDownloadHelper.updateDownloadText()

        val galleryInfo = getGalleryInfo()
        bindReadProgress(galleryInfo)

        mSize!!.text = gd.size

        // LANraragi rating display
        if (gd.rating > 0) {
            mRatingText!!.text = String.format("%.0f\u2605", gd.rating)
            mRating!!.rating = gd.rating
        } else {
            mRatingText!!.text = "Not rated"
            mRating!!.rating = 0f
        }

        updateFavoriteDrawable()
        bindArchiverProgress(gd)
        mTagHelper.bindTags(gd.tags)
    }

    fun bindArchiverProgress(gd: GalleryDetail) {
        mArchiverDownloadProgress?.initThread(gd)
    }

    private fun bindReadProgress(info: GalleryInfo?) {
        if (info == null) return
        if (mContext == null) {
            mContext = getEHContext()
            if (mContext == null) {
                return
            }
        }
        val ctx = mContext!!
        if (executorService == null) {
            executorService = viewModel.executorService
        }

        executorService!!.submit {
            val startPage = SpiderQueen.findStartPage(ctx, info)
            val pages = info.pages
            val text: String = if (startPage > 0) {
                "${startPage + 1}/${pages}P"
            } else {
                "0/${pages}P"
            }
            handler.post {
                mPages?.text = text
            }
        }
    }

    private fun setTransitionName() {
        val gid = getGid()

        if (gid != -1L && mThumb != null && mTitle != null && mUploader != null) {
            ViewCompat.setTransitionName(mThumb!!, TransitionNameFactory.getThumbTransitionName(gid))
            ViewCompat.setTransitionName(mTitle!!, TransitionNameFactory.getTitleTransitionName(gid))
            ViewCompat.setTransitionName(mUploader!!, TransitionNameFactory.getUploaderTransitionName(gid))
        }
    }

    @SuppressLint("NonConstantResourceId")
    private fun ensurePopMenu() {
        if (mPopupMenu != null || mOtherActions == null) {
            return
        }

        val ctx = getEHContext()
        AssertUtils.assertNotNull(ctx)
        val popup = PopupMenu(ctx!!, mOtherActions!!, Gravity.TOP)
        mPopupMenu = popup
        popup.menuInflater.inflate(R.menu.scene_gallery_detail, popup.menu)
        // Show LANraragi-specific menu items only when connected
        val isLrrConnected = LRRAuthManager.getServerUrl() != null
        val deleteItem = popup.menu.findItem(R.id.action_lrr_delete)
        deleteItem?.isVisible = isLrrConnected
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_open_in_other_app -> {
                    val url = getGalleryDetailUrl()
                    val act = activity2
                    if (url != null && act != null) {
                        UrlOpener.openUrl(act, url, false)
                    }
                }
                R.id.action_refresh -> {
                    if (mState != STATE_REFRESH && mState != STATE_REFRESH_HEADER) {
                        adjustViewVisibility(STATE_REFRESH, true)
                        request()
                    }
                }
                R.id.action_lrr_delete -> {
                    DeleteArchiveHelper.show(activity2, mGalleryInfo) { title ->
                        showTip(getString(R.string.lrr_delete_success, title), LENGTH_LONG)
                        onBackPressed()
                    }
                }
            }
            true
        }
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
        } else if (mOtherActions === v) {
            ensurePopMenu()
            mPopupMenu?.show()
        } else if (mUploader === v) {
            val uploader = getUploader()
            if (TextUtils.isEmpty(uploader)) {
                return
            }
            val lub = ListUrlBuilder()
            lub.mode = ListUrlBuilder.MODE_UPLOADER
            lub.keyword = uploader
            GalleryListScene.startScene(this, lub)
        } else if (mDownload === v) {
            mDownloadHelper.onDownload()
        } else if (mRead === v) {
            val galleryInfo: GalleryInfo? = mGalleryInfo ?: mGalleryDetail
            if (galleryInfo != null) {
                val intent = GalleryOpenHelper.buildReadIntent(requireActivity(), galleryInfo)
                startActivity(intent)
            }
        } else if (mHeartGroup === v) {
            // LANraragi: Show category selection dialog
            if (mGalleryDetail != null) {
                CategoryDialogHelper.showCategoryDialog(
                    requireActivity(), mGalleryDetail!!
                ) { isFavorited, favoriteName ->
                    if (mGalleryDetail != null) {
                        mGalleryDetail!!.isFavorited = isFavorited
                        mGalleryDetail!!.favoriteName = favoriteName
                        updateFavoriteDrawable()
                    }
                }
            }
        } else if (mTitle === v) {
            if (mGalleryDetail?.title != null) {
                ClipboardUtil.copyText(mGalleryDetail!!.title)
                Toast.makeText(getContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
            }
        } else {
            val o = v.getTag(R.id.tag)
            if (o is String) {
                val lub = ListUrlBuilder()
                lub.mode = ListUrlBuilder.MODE_TAG
                lub.keyword = o
                GalleryListScene.startScene(this, lub)
                return
            }

            // The v.getTag(R.id.index) path was an EhViewer-era preview-thumbnail
            // click handler that opened the gallery reader at a specific page via
            // GalleryActivity.ACTION_EH. The preview grid has been dead since the
            // LRR conversion (LRRArchive hardcodes previewPages = 0 and nothing
            // ever calls setTag(R.id.index, ...) on any view), so this branch is
            // unreachable and has been removed as a follow-up to the
            // EhGalleryProvider investigation (2026-04-08).
        }
    }

    override fun onLongClick(v: View): Boolean {
        mContext = getEHContext()
        activity = activity2
        if (activity == null) {
            return false
        }

        if (mDownload === v) {
            mDownloadHelper.onDownload()
            return true
        } else if (v === mHeartGroup) {
            // Long press also shows category dialog (same as click)
            if (mGalleryDetail != null) {
                CategoryDialogHelper.showCategoryDialog(
                    requireActivity(), mGalleryDetail!!
                ) { isFavorited, favoriteName ->
                    if (mGalleryDetail != null) {
                        mGalleryDetail!!.isFavorited = isFavorited
                        mGalleryDetail!!.favoriteName = favoriteName
                        updateFavoriteDrawable()
                    }
                }
            }
        } else {
            val tag = v.getTag(R.id.tag) as? String
            if (tag != null) {
                mTagHelper.showTagDialog(this, tag)
                return true
            }
        }

        return false
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
        mDownloadHelper.updateDownloadState()
        if (mDownloadHelper.downloadState != DownloadInfo.STATE_INVALID) {
            val di = mDownloadInfo
            if (di != null && di.thumb != null &&
                di.thumb != result.thumb && di.gid == result.gid
            ) {
                useNetWorkLoadThumb = true
                di.updateInfo(result)
                di.state = mDownloadHelper.downloadState
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
