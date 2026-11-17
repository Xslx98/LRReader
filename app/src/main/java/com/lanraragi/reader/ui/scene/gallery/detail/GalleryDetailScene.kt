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

package com.lanraragi.reader.ui.scene.gallery.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.TextView
import com.lanraragi.framework.util.isLightTheme
import com.lanraragi.reader.R
import androidx.lifecycle.ViewModelProvider
import com.lanraragi.reader.domain.ArchiveDetail
import com.lanraragi.reader.ui.scene.BaseScene
import com.lanraragi.reader.widget.ArchiverDownloadProgress
import com.lanraragi.framework.util.AssertUtils
import com.lanraragi.framework.util.ViewUtils
import com.lanraragi.reader.widget.Ripples
import androidx.core.content.ContextCompat
import com.lanraragi.reader.widget.ViewTransition
import com.lanraragi.reader.widget.LoadImageView
import com.lanraragi.reader.ui.readableErrorText

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

    private val mSourceError by lazy {
        DetailSourceErrorBanner(
            viewModel,
            context = { getEHContext() },
            onRetry = { requestRefresh() },
        )
    }

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
     Page-thumbnail grid
     ---------------*/
    private lateinit var pageThumbsViewModel: PageThumbnailsViewModel
    private lateinit var pageThumbs: DetailPageThumbs

    /*---------------
     Extracted helpers
     ---------------*/
    private var mHeaderBinder: DetailHeaderBinder? = null
    private var mTankBinder: DetailTankoubonBinder? = null
    private var mActionHandler: DetailActionHandler? = null
    private val mRating by lazy { DetailRatingController(viewModel) }
    private val mEditActions by lazy { DetailEditActions(this, viewModel) }

    // Read by GalleryDetailEvents.
    internal val sourceError: DetailSourceErrorBanner get() = mSourceError
    internal val ratingController: DetailRatingController get() = mRating
    internal val actionHandler: DetailActionHandler? get() = mActionHandler
    internal val tankBinder: DetailTankoubonBinder? get() = mTankBinder
    internal val headerBinder: DetailHeaderBinder? get() = mHeaderBinder

    /** The state this view last rendered; null for a fresh view. */
    private var renderedState: DetailState? = null

    /** The scene's first view after opening the page: it reveals the header once shown. */
    private var firstView = false

    /** Shortcut delegating to [GalleryDetailViewModel.action]. */
    private var mAction: String?
        get() = viewModel.action.value
        set(value) { viewModel.setAction(value) }

    /** Shortcut delegating to [GalleryDetailViewModel.archive]. */
    private var mArchive: com.lanraragi.reader.domain.Archive?
        get() = viewModel.archive.value
        set(value) { viewModel.setArchive(value) }

    /** Shortcut delegating to [GalleryDetailViewModel.arcid]. */
    private var mArcid: String?
        get() = viewModel.arcid.value
        set(value) { viewModel.setArcid(value) }





    private fun handleArgs(args: Bundle?) {
        if (args == null) {
            return
        }

        val action = args.getString(KEY_ACTION)
        mAction = action
        if (ACTION_ARCHIVE == action) {
            val archive: com.lanraragi.reader.domain.Archive? = args.getParcelable(KEY_ARCHIVE)
            if (archive != null) {
                mArchive = archive
                mArcid = archive.arcid
                // Opening the detail page is browsing, not reading: no history
                // row here. The reader records history when a session starts
                // (audit 2026-10-04, user ruling extending C03).
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(this)[GalleryDetailViewModel::class.java]
        // Scene-scoped like [viewModel]; each detail scene owns both.
        pageThumbsViewModel =
            ViewModelProvider(this)[PageThumbnailsViewModel::class.java]
        pageThumbs = DetailPageThumbs(this, viewModel, pageThumbsViewModel)
        // A fragment result, not a scene result: scene requests do not survive a
        // rotation on the plugin screen. Delivered once this scene is started again.
        parentFragmentManager.setFragmentResultListener(PluginTagScene.resultKey(tag), this) { _, _ ->
            requestRefresh()
        }

        if (savedInstanceState == null) {
            firstView = true
            onInit()
        } else {
            onRestore(savedInstanceState)
        }
    }

    private fun onInit() {
        handleArgs(arguments)
    }

    private fun onRestore(savedInstanceState: Bundle) {
        mAction = savedInstanceState.getString(KEY_ACTION)
        mArchive = savedInstanceState.getParcelable(KEY_ARCHIVE)
        mArcid = savedInstanceState.getString(KEY_ARCID)
        savedInstanceState.getParcelable<ArchiveDetail>(KEY_ARCHIVE_DETAIL)?.let {
            viewModel.setArchiveDetail(it)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (mAction != null) {
            outState.putString(KEY_ACTION, mAction)
        }
        viewModel.archive.value?.let { outState.putParcelable(KEY_ARCHIVE, it) }
        if (mArcid != null) {
            outState.putString(KEY_ARCID, mArcid)
        }
        viewModel.archiveDetail.value?.let {
            outState.putParcelable(KEY_ARCHIVE_DETAIL, it)
        }
    }

    override fun onCreateView2(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val context = getEHContext()
        // Get download state
        viewModel.initDownloadState(viewModel.getEffectiveArcid())

        val view = inflater.inflate(R.layout.scene_gallery_detail, container, false)

        val main = ViewUtils.requireView(view, R.id.main) as ViewGroup
        val mainView = ViewUtils.requireView(main, R.id.scroll_view)
        val progressView = ViewUtils.requireView(main, R.id.progress_view)
        mTip = ViewUtils.requireView(main, R.id.tip) as TextView
        mViewTransition = ViewTransition(mainView, progressView, mTip)

        assert(context != null)
        AssertUtils.assertNotNull(context)

        val actionsScrollView = ViewUtils.requireView(view, R.id.actions_scroll_view)
        setDrawerGestureBlocker(ScrollAreaGestureBlocker(actionsScrollView) { isDrawersVisible() })

        val nonNullContext = requireContext()
        val drawable = ContextCompat.getDrawable(nonNullContext, R.drawable.big_sad_pandroid)
        drawable?.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        val tip = mTip ?: return null
        tip.setCompoundDrawables(null, drawable, null, null)
        tip.setOnClickListener(this)

        val belowHeader: View = mainView.findViewById(R.id.below_header)
        mBelowHeader = belowHeader
        mSourceError.bind(belowHeader)
        val isDarkTheme = !nonNullContext.isLightTheme()
        mHeader = ViewUtils.requireView(belowHeader, R.id.header)
        val colorBg = ViewUtils.requireView(mHeader, R.id.color_bg)
        val thumb = ViewUtils.requireView(mHeader, R.id.thumb) as LoadImageView
        val title = ViewUtils.requireView(mHeader, R.id.title) as TextView
        val uploader = ViewUtils.requireView(mHeader, R.id.uploader) as TextView
        val otherActions = ViewUtils.requireView(mHeader, R.id.other_actions) as ImageView
        mActionGroup = ViewUtils.requireView(mHeader, R.id.action_card) as ViewGroup
        val download = ViewUtils.requireView(mActionGroup, R.id.download) as TextView
        val archiverDownloadProgress = ViewUtils.requireView(mHeader, R.id.archiver_download_progress) as ArchiverDownloadProgress
        val read = ViewUtils.requireView(mActionGroup, R.id.read)
        mRead = read
        Ripples.addRipple(otherActions, isDarkTheme)
        Ripples.addRipple(download, isDarkTheme)
        Ripples.addRipple(read, isDarkTheme)
        uploader.setOnClickListener(this)
        otherActions.setOnClickListener(this)
        download.setOnClickListener(this)
        download.setOnLongClickListener(this)
        read.setOnClickListener(this)
        title.setOnClickListener(this)

        uploader.setOnLongClickListener(this)

        val infoView = ViewUtils.requireView(belowHeader, R.id.info)
        val pages = ViewUtils.requireView(infoView, R.id.pages) as TextView
        val size = ViewUtils.requireView(infoView, R.id.size) as TextView

        mActions = ViewUtils.requireView(belowHeader, R.id.actions)
        val ratingText = ViewUtils.requireView(mActions, R.id.rating_text) as TextView
        val rating = ViewUtils.requireView(mActions, R.id.rating) as RatingBar
        val heartGroup = ViewUtils.requireView(mActions, R.id.heart_group)
        mHeartGroup = heartGroup
        val heart = ViewUtils.requireView(heartGroup, R.id.heart) as TextView
        val heartOutline = ViewUtils.requireView(heartGroup, R.id.heart_outline) as TextView
        Ripples.addRipple(heartGroup, isDarkTheme)
        heartGroup.setOnClickListener(this)
        heartGroup.setOnLongClickListener(this)

        val tags = ViewUtils.requireView(belowHeader, R.id.tags) as LinearLayout
        val noTags = ViewUtils.requireView(tags, R.id.no_tags) as TextView
        val sourceBadge = ViewUtils.requireView(mHeader, R.id.source_badge) as TextView

        // Initialize helpers
        val headerBinder = DetailHeaderBinder(
            viewModel, viewLifecycleOwner,
            thumb, title, uploader, pages, size,
            ratingText, rating, heart, heartOutline,
            archiverDownloadProgress, colorBg, tags, noTags,
            sourceBadge,
        )
        mHeaderBinder = headerBinder
        headerBinder.ensureActionDrawable(nonNullContext)

        val tankSection = ViewUtils.requireView(belowHeader, R.id.detail_tankoubons_section)
        mTankBinder = DetailTankoubonBinder(
            section = tankSection,
            container = ViewUtils.requireView(tankSection, R.id.detail_tankoubons_container) as LinearLayout,
            edit = ViewUtils.requireView(tankSection, R.id.detail_tankoubons_edit) as TextView,
            onTankClick = { tank -> mEditActions.openTankoubonDetail(tank) },
            onEditClick = { mEditActions.showTankMembershipDialog() },
        )

        val actionHandler = DetailActionHandler(this, viewModel, viewLifecycleOwner)
        mActionHandler = actionHandler
        actionHandler.otherActions = otherActions
        actionHandler.download = download
        actionHandler.onFavoriteChanged = { arcid ->
            mHeaderBinder?.updateFavoriteDrawable(arcid)
        }

        mRating.bind(rating, ratingText)

        mEditTagsBtn = ViewUtils.requireView(tags, R.id.edit_tags_btn) as? android.widget.ImageButton
        mEditActions.bindEditTagsButton(mEditTagsBtn)
        mEditActions.bindPluginTagsButton(ViewUtils.requireView(tags, R.id.plugin_tags_btn) as? android.widget.ImageButton)

        mProgress = ViewUtils.requireView(mainView, R.id.progress)

        mViewTransition2 = ViewTransition(mBelowHeader, mProgress)

        viewModel.open(getString(R.string.lrr_category_info_suffix), getString(R.string.lrr_category_count_suffix))
        render(viewModel.state.value)


        pageThumbs.setUp(view)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        GalleryDetailEvents(this, viewModel).install(viewLifecycleOwner, view)

        // ----- Page-thumbnail grid wiring -----
        pageThumbs.observe(viewLifecycleOwner)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Leaving the page (not a rotation, not the reader opening — that is
        // another activity) ends its speculative reading preload.
        if (activity?.isChangingConfigurations != true) viewModel.cancelReadingPreload()

        val context = getEHContext()
        AssertUtils.assertNotNull(context)
        GalleryTagHelper.destroy()

        setDrawerGestureBlocker(null)

        mTip = null
        mViewTransition = null
        renderedState = null

        mHeader = null
        mActionGroup = null
        mRead = null
        mBelowHeader = null
        mSourceError.clear()

        mActions = null
        mHeartGroup = null
        mEditTagsBtn = null

        mProgress = null
        mViewTransition2 = null

        mHeaderBinder = null
        mTankBinder = null
        mActionHandler?.destroy()
        mActionHandler = null

        pageThumbs.clear()
    }

    /** Fetches the detail again unless a fetch already runs. */
    internal fun requestRefresh() {
        viewModel.refresh(getString(R.string.lrr_category_info_suffix), getString(R.string.lrr_category_count_suffix))
    }

    /** Shows [state] on this view; the transitions themselves are the ViewModel's. */
    internal fun render(state: DetailState) {
        val viewTransition = mViewTransition ?: return
        val viewTransition2 = mViewTransition2 ?: return
        val previous = renderedState
        if (state == previous) return
        renderedState = state

        val headerShown = state is DetailState.Shown || state == DetailState.LoadingBelowHeader
        if (previous == null && headerShown) mHeaderBinder?.setTransitionName(viewModel.getEffectiveArcid())
        when (state) {
            is DetailState.Shown -> {
                viewTransition.showView(0, false)
                viewTransition2.showView(0, false)
                renderShown(state, previous)
            }
            DetailState.LoadingBelowHeader -> {
                if (previous == null) bindViewFirst()
                viewTransition.showView(0, false)
                viewTransition2.showView(1, false)
            }
            DetailState.Init, DetailState.Loading -> viewTransition.showView(1, false)
            is DetailState.Failed -> {
                val error = state.error
                mTip?.text = if (error != null) readableErrorText(error) else getString(R.string.error_cannot_find_gallery)
                viewTransition.showView(2, false)
            }
        }

        revealHeader(from = previous ?: if (firstView) DetailState.Init else null, headerShown)
        firstView = false
    }

    private fun renderShown(state: DetailState.Shown, previous: DetailState?) {
        // A fresh load (same content too), the degraded detail seeded on
        // failure (or both heart labels stay visible over each other, #94),
        // or a new view.
        if (previous !is DetailState.Shown || previous.load != state.load) {
            if (viewModel.syncDownloadRow()) mHeaderBinder?.useNetWorkLoadThumb = true
            bindViewSecond()
        }
        val error = state.sourceError
        if (error != null) mSourceError.show(error) else mSourceError.hide()
    }

    /**
     * The header reveals once when it first appears after loading; not on a
     * re-created view of a page already shown ([from] null).
     */
    private fun revealHeader(from: DetailState?, headerShown: Boolean) {
        val wasLoading = from == DetailState.Init || from == DetailState.Loading || from is DetailState.Failed
        val context = getEHContext() ?: return
        if (wasLoading && headerShown && context.isLightTheme()) {
            mHeaderBinder?.createCircularRevealOrPost()
        }
    }

    private fun bindViewFirst() {
        if (viewModel.archiveDetail.value != null) return
        val binder = mHeaderBinder ?: return
        binder.bindViewFirst(mAction, viewModel.archive.value)
        mActionHandler?.updateDownloadText()
    }

    private fun bindViewSecond() {
        try {
            val ad = viewModel.archiveDetail.value ?: return
            val binder = mHeaderBinder ?: return
            binder.bindViewSecond(ad, mArchive != null, getEHContext(), layoutInflater2, this, this)
            mActionHandler?.updateDownloadText()
        } catch (e: Exception) {
            android.util.Log.e("GalleryDetailScene", "bindViewSecond crashed", e)
        }
    }

    fun bindArchiverProgress(arcid: String) {
        mHeaderBinder?.bindArchiverProgress(arcid)
    }

    override fun onClick(v: View) {
        val ctx = getEHContext() ?: return
        val activity = activity2 ?: return

        if (mTip === v) {
            requestRefresh()
        } else {
            mActionHandler?.onClick(v, ctx, activity)
        }
    }

    override fun onLongClick(v: View): Boolean {
        val ctx = getEHContext() ?: return false
        val activity = activity2 ?: return false
        return mActionHandler?.onLongClick(v, ctx, activity) ?: false
    }


    companion object {

        const val KEY_ACTION = "action"
        const val ACTION_ARCHIVE = "action_archive"

        const val KEY_ARCHIVE = "archive"
        const val KEY_ARCID = "token"
        const val KEY_PAGE = "page"

        private const val KEY_ARCHIVE_DETAIL = "archive_detail"
    }
}
