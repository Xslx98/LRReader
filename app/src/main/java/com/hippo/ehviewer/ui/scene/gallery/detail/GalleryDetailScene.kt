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
import android.text.InputType
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RatingBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.hippo.android.resource.AttrResources
import com.hippo.drawerlayout.DrawerLayout
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.settings.AppearanceSettings
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.domain.ArchiveDetail
import com.lanraragi.reader.domain.buildRatingEmoji
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.ui.scene.TankoubonDetailScene
import com.hippo.ehviewer.util.collectFlow
import com.hippo.ehviewer.util.collectFlowWhileCreated
import com.hippo.scene.Announcer
import com.lanraragi.reader.client.api.LRRTankoubonApi
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
import com.hippo.ehviewer.download.DownloadState

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

    // Source-server error banner (shown when the source LANraragi
    // server is unreachable / orphan / has deleted the archive but
    // we still have local-cache data to display).
    private var mSourceErrorBanner: View? = null
    private var mSourceErrorText: TextView? = null
    private var mSourceErrorRetry: View? = null

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
    private var mPageThumbRecycler: RecyclerView? = null
    private var mPageThumbProgress: ProgressBar? = null
    private var mPageThumbEmpty: TextView? = null
    private var mPageThumbAdapter: PageThumbnailAdapter? = null
    private var mPageThumbSpanCount: Int = 0

    /*---------------
     Extracted helpers
     ---------------*/
    private var mHeaderBinder: DetailHeaderBinder? = null
    private var mTankBinder: DetailTankoubonBinder? = null
    private var mActionHandler: DetailActionHandler? = null

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

    private var mRequestId: Int = IntIdGenerator.INVALID_ID
    /** Rating when the scene was opened, used to detect changes on exit. */
    private var mInitialRating: Float = Float.NaN

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
        // Same Activity-scoping concern for the thumbnail grid VM —
        // without this, navigating from archive A to archive B would
        // briefly show A's pageStates against B's archive.
        pageThumbsViewModel.resetForNewEntry()

        val action = args.getString(KEY_ACTION)
        mAction = action
        if (ACTION_ARCHIVE == action) {
            val archive: com.lanraragi.reader.domain.Archive? = args.getParcelable(KEY_ARCHIVE)
            if (archive != null) {
                mArchive = archive
                mArcid = archive.arcid
                viewModel.recordHistory(archive)
            }
        }
    }

    /**
     * The [GalleryDetailViewModel] is Activity-scoped and shared across every stacked
     * GalleryDetailScene. On re-attach (a sibling detail was pushed then popped) it may
     * still hold the other scene's archive, which would render here as the wrong gallery
     * — and route ratings/edits to the wrong arcid. This scene's own identity lives only
     * in its nav [arguments]; if the VM's effective arcid no longer matches, replay
     * [handleArgs] to reset and re-point both view models before binding.
     */
    private fun reassertOwnEntryIfStale() {
        val args = arguments ?: return
        val ownArcid = args.getParcelable<com.lanraragi.reader.domain.Archive>(KEY_ARCHIVE)?.arcid ?: return
        if (viewModel.getEffectiveArcid() != ownArcid) {
            handleArgs(args)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[GalleryDetailViewModel::class.java]
        // Activity-scoped just like [viewModel]; obtaining it before onInit()
        // so handleArgs() can call resetForNewEntry() on it during a new
        // archive entry, mirroring [GalleryDetailViewModel.resetForNewEntry].
        pageThumbsViewModel =
            ViewModelProvider(requireActivity())[PageThumbnailsViewModel::class.java]

        if (savedInstanceState == null) {
            onInit()
        } else {
            onRestore(savedInstanceState)
        }

        val a = mArchive
        if (properties == null && a != null) {
            val date = Date()
            @SuppressLint("SimpleDateFormat")
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            properties = HashMap<String, String>().apply {
                put("Title", a.title)
                put("Time", dateFormat.format(date))
            }
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
        mRequestId = savedInstanceState.getInt(KEY_REQUEST_ID)
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
        outState.putInt(KEY_REQUEST_ID, mRequestId)
    }

    override fun onCreateView2(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val context = getEHContext()
        // Re-point the shared Activity-scoped ViewModel at this scene's archive if a
        // sibling detail scene left it pointing elsewhere, before reading any VM state.
        reassertOwnEntryIfStale()
        // Get download state
        viewModel.initDownloadState(viewModel.getEffectiveArcid())

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
        val tip = mTip ?: return null
        tip.setCompoundDrawables(null, drawable, null, null)
        tip.setOnClickListener(this)

        val belowHeader: View = mainView.findViewById(R.id.below_header)
        mBelowHeader = belowHeader
        mSourceErrorBanner = belowHeader.findViewById(R.id.source_error_banner)
        mSourceErrorText = belowHeader.findViewById(R.id.source_error_text)
        mSourceErrorRetry = belowHeader.findViewById(R.id.source_error_retry)
        mSourceErrorRetry?.setOnClickListener {
            mSourceErrorBanner?.visibility = View.GONE
            requestRefresh()
        }
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
        val read = ViewUtils.`$$`(mActionGroup, R.id.read)
        mRead = read
        Ripple.addRipple(otherActions, isDarkTheme)
        Ripple.addRipple(download, isDarkTheme)
        Ripple.addRipple(read, isDarkTheme)
        uploader.setOnClickListener(this)
        otherActions.setOnClickListener(this)
        download.setOnClickListener(this)
        download.setOnLongClickListener(this)
        read.setOnClickListener(this)
        title.setOnClickListener(this)

        uploader.setOnLongClickListener(this)

        val infoView = ViewUtils.`$$`(belowHeader, R.id.info)
        val pages = ViewUtils.`$$`(infoView, R.id.pages) as TextView
        val size = ViewUtils.`$$`(infoView, R.id.size) as TextView

        mActions = ViewUtils.`$$`(belowHeader, R.id.actions)
        val ratingText = ViewUtils.`$$`(mActions, R.id.rating_text) as TextView
        val rating = ViewUtils.`$$`(mActions, R.id.rating) as RatingBar
        val heartGroup = ViewUtils.`$$`(mActions, R.id.heart_group)
        mHeartGroup = heartGroup
        val heart = ViewUtils.`$$`(heartGroup, R.id.heart) as TextView
        val heartOutline = ViewUtils.`$$`(heartGroup, R.id.heart_outline) as TextView
        Ripple.addRipple(heartGroup, isDarkTheme)
        heartGroup.setOnClickListener(this)
        heartGroup.setOnLongClickListener(this)

        val tags = ViewUtils.`$$`(belowHeader, R.id.tags) as LinearLayout
        val noTags = ViewUtils.`$$`(tags, R.id.no_tags) as TextView
        val sourceBadge = ViewUtils.`$$`(mHeader, R.id.source_badge) as TextView

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

        val tankSection = ViewUtils.`$$`(belowHeader, R.id.detail_tankoubons_section)
        mTankBinder = DetailTankoubonBinder(
            section = tankSection,
            container = ViewUtils.`$$`(tankSection, R.id.detail_tankoubons_container) as LinearLayout,
            edit = ViewUtils.`$$`(tankSection, R.id.detail_tankoubons_edit) as TextView,
            onTankClick = { tank -> openTankoubonDetail(tank) },
            onEditClick = { showTankMembershipDialog() },
        )

        val actionHandler = DetailActionHandler(this, viewModel, viewLifecycleOwner)
        mActionHandler = actionHandler
        actionHandler.otherActions = otherActions
        actionHandler.download = download
        actionHandler.onFavoriteChanged = { arcid ->
            mHeaderBinder?.updateFavoriteDrawable(arcid)
        }

        // Make rating bar interactive. LANraragi only supports integer
        // ratings (1-5), but we keep stepSize=0.5 for smooth drag feedback.
        // The value is ceiled to an integer on touch release.
        rating.setIsIndicator(false)
        rating.stepSize = 0.5f
        rating.onRatingBarChangeListener =
            RatingBar.OnRatingBarChangeListener { _, ratingValue, fromUser ->
                if (!fromUser || viewModel.archiveDetail.value == null) return@OnRatingBarChangeListener
                // Live preview: show the emoji for the rounded value while dragging
                ratingText.text = buildRatingEmoji(
                    kotlin.math.ceil(ratingValue).toInt()
                )
            }
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        rating.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP
                || event.action == android.view.MotionEvent.ACTION_CANCEL
            ) {
                // Defer the read to the next frame. dispatchTouchEvent
                // calls OnTouchListener BEFORE View.onTouchEvent, so
                // reading rating.rating synchronously here snapshots the
                // pre-touch value on a *tap* gesture (DOWN→UP with no
                // MOVE in between). Drag still appears to work because
                // MOVE events let RatingBar.onTouchEvent update the
                // progress en route. post() runs after RatingBar's own
                // onTouchEvent has applied the UP coordinates.
                rating.post {
                    if (viewModel.archiveDetail.value == null) return@post
                    // Ceil to integer: 0.5→1, 1.5→2, 4.5→5, etc.
                    val finalRating = kotlin.math.ceil(rating.rating).coerceIn(0f, 5f)
                    rating.rating = finalRating
                    ratingText.text = if (finalRating > 0f) {
                        buildRatingEmoji(finalRating.toInt())
                    } else {
                        "Not rated"
                    }
                    // Single business entry point: the ViewModel does the
                    // optimistic write across every SSOT and runs the PUT.
                    // The Scene only renders.
                    viewModel.submitRating(finalRating)
                }
            }
            false // Don't consume — let RatingBar handle the touch
        }

        mEditTagsBtn = ViewUtils.`$$`(tags, R.id.edit_tags_btn) as? android.widget.ImageButton
        mEditTagsBtn?.let { btn ->
            val isLrr = LRRAuthManager.getServerUrl() != null
            btn.visibility = if (isLrr) View.VISIBLE else View.GONE
            btn.setOnClickListener {
                val ad = viewModel.archiveDetail.value
                if (ad != null) {
                    TagEditDialog.show(
                        activity2, ad.archive.arcid,
                        ad.tagGroups, viewModel.getSourceProfileId()
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
            if (viewModel.archiveDetail.value != null) {
                bindViewSecond()
                mHeaderBinder?.setTransitionName(viewModel.getEffectiveArcid())
                adjustViewVisibility(STATE_NORMAL, false)
            } else if (mArchive != null) {
                bindViewFirst()
                mHeaderBinder?.setTransitionName(viewModel.getEffectiveArcid())
                adjustViewVisibility(STATE_REFRESH_HEADER, false)
            } else {
                adjustViewVisibility(STATE_REFRESH, false)
            }
        } else {
            tip.setText(R.string.error_cannot_find_gallery)
            adjustViewVisibility(STATE_FAILED, false)
        }

        viewModel.downloadManager.addDownloadInfoListener(viewModel.downloadInfoListener)

        setupPageThumbnailGrid(view)

        return view
    }

    /**
     * Wire up the per-page thumbnail grid at the bottom of the
     * detail page. Splits the spanCount calculation, adapter
     * binding and scroll-listener install into one place so the
     * onCreateView2 critical path stays readable.
     */
    private fun setupPageThumbnailGrid(view: View) {
        val recycler = view.findViewById<RecyclerView>(R.id.page_thumb_recycler) ?: return
        val progress = view.findViewById<ProgressBar>(R.id.page_thumb_progress)
        val empty = view.findViewById<TextView>(R.id.page_thumb_empty)
        val jumpButton = view.findViewById<TextView>(R.id.page_thumb_jump)

        mPageThumbRecycler = recycler
        mPageThumbProgress = progress
        mPageThumbEmpty = empty

        val displayMetrics = resources.displayMetrics
        // Target tile width is resolved at bind time from the user's
        // AppearanceSettings.KEY_DETAIL_PAGE_THUMB_SIZE preference,
        // not hard-coded — so a user can pick a chunkier (large)
        // or denser (small) grid without rebuilding the app. SPAN_MIN
        // / SPAN_MAX still clamp the result so an extreme display
        // never collapses the grid or runs more than 6 columns.
        val targetWidthPx = resources.getDimensionPixelSize(
            AppearanceSettings.getDetailPageThumbSizeResId()
        ).coerceAtLeast(1)
        val spanCount = (displayMetrics.widthPixels / targetWidthPx)
            .coerceIn(SPAN_MIN, SPAN_MAX)
        mPageThumbSpanCount = spanCount

        val adapter = PageThumbnailAdapter(
            onPageClick = { page0 -> openReaderAtPage(page0) },
            onPageRetry = { page0 -> pageThumbsViewModel.retryPage(page0) },
        )
        mPageThumbAdapter = adapter

        recycler.layoutManager = GridLayoutManager(view.context, spanCount)
        // Disable item-change animations so the partial rebind on
        // notifyItemChanged(pos, PAYLOAD) does not flash the thumbnail.
        recycler.itemAnimator = null
        recycler.adapter = adapter

        recycler.addOnScrollListener(
            PrefetchScrollListener(spanCount) { page ->
                pageThumbsViewModel.requestPage(page)
            }
        )

        jumpButton?.setOnClickListener { showJumpToPageDialog() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // UI-4: every collector below is view-scoped. The scene framework
        // detaches covered scenes (view destroyed, fragment alive); the previous
        // fragment-scoped launches stacked one collector set per re-attach.

        // Capture the server-side rating exactly once per detail-page
        // entry. The cache-hit / process-death-restore paths take
        // STATE_NORMAL straight from `prepareData` and never reach
        // `onGetArchiveDetailSuccessInternal`, so the capture lives here.
        // (StateFlow replay + the isNaN guard make re-subscription a no-op.)
        collectFlow(viewLifecycleOwner, viewModel.archiveDetail) { ad ->
            if (ad != null && mInitialRating.isNaN()) {
                mInitialRating = ad.archive.rating
            }
        }
        // Drive REFRESH→NORMAL transitions off the dedicated load-event
        // SharedFlow rather than the deduped archiveDetail StateFlow.
        // A refresh that returns identical content would otherwise leave
        // the spinner up forever (StateFlow distinct-emit eats the value
        // and `collect` never fires). detailLoaded fires unconditionally
        // on every successful API response.
        // collectFlowWhileCreated: a refresh completing while the app is
        // backgrounded must still flip mState — STARTED gating would drop
        // the event and leave a permanent spinner on return.
        collectFlowWhileCreated(viewLifecycleOwner, viewModel.detailLoaded) { ad ->
            if (mState != STATE_NORMAL) {
                onGetArchiveDetailSuccess(ad)
            }
            // A successful refresh after a banner-flagged failure also dismisses
            // the source-error banner — the live data is now displayed and the
            // warning no longer applies.
            mSourceErrorBanner?.visibility = View.GONE
        }
        // collectFlowWhileCreated: error arm of the same state machine.
        collectFlowWhileCreated(viewLifecycleOwner, viewModel.detailError) { e ->
            onGetGalleryDetailFailure(e)
        }
        // Cache-first failure: page is already painted from local data
        // (LRU cache or a degraded ArchiveDetail seeded from the nav-arg
        // Archive). Show a non-blocking banner so the user can read the
        // archive offline and retry the source server when it returns.
        // collectFlowWhileCreated: banner state must not be lost to a
        // backgrounded window.
        collectFlowWhileCreated(viewLifecycleOwner, viewModel.detailErrorBanner) { e ->
            showSourceErrorBanner(e)
        }
        // After a rollback the optimistic write to currentRating /
        // archiveDetail has been reverted on the IO thread; reflect the
        // restored value on the rating bar and toast the error so the
        // user knows their change did not stick.
        // collectFlowWhileCreated: losing the event would leave the rating
        // bar showing the rolled-back optimistic value.
        collectFlowWhileCreated(viewLifecycleOwner, viewModel.ratingError) { e ->
            rebindRatingFromViewModel()
            val ctx = getEHContext()
            if (ctx != null) {
                android.widget.Toast.makeText(
                    ctx,
                    com.lanraragi.reader.client.api.friendlyError(ctx, e),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
        // Observe download state changes from ViewModel (replaces DownloadHelper listener)
        collectFlow(viewLifecycleOwner, viewModel.downloadState) {
            mActionHandler?.updateDownloadText()
        }
        // Observe local reading progress so the header refreshes whenever the
        // reader writes a new page (StateFlow replays its current value to new
        // collectors, so this also covers returning from the reader to a paused
        // detail Scene). Replaces the previous one-shot onResume re-read.
        collectFlow(viewLifecycleOwner, viewModel.localReadingPage) { localPage0 ->
            applyLocalReadingProgress(localPage0)
        }
        // "Belongs to Tankoubons" section — an empty list keeps it hidden.
        collectFlow(viewLifecycleOwner, viewModel.archiveTankoubons) { tanks ->
            mTankBinder?.bind(tanks)
        }

        // ----- Page-thumbnail grid wiring -----
        // Kick off the page-list fetch as soon as we have an archive in
        // the nav-arg. Independent of detail metadata so it runs in
        // parallel with `requestGalleryDetail` and we don't gate the
        // thumbnail grid behind a slow metadata response.
        // Raw view-scoped launch: a per-attach one-shot (`first()`), must NOT
        // sit inside repeatOnLifecycle or it would re-run on every foreground
        // return.
        viewLifecycleOwner.lifecycleScope.launch(
            ServiceRegistry.coroutineModule.exceptionHandler
        ) {
            val arc = viewModel.archive.filterNotNull().first()
            mPageThumbAdapter?.submitArcid(arc.arcid)
            pageThumbsViewModel.start(arc.arcid, arc.serverProfileId)
        }
        // State machine drives top-level visibility: spinner vs empty
        // text vs RecyclerView populated with pageCount empty slots.
        collectFlow(viewLifecycleOwner, pageThumbsViewModel.state) { state ->
            applyPageThumbsState(state)
        }
        // Per-page status snapshot drives partial cell rebinds.
        collectFlow(viewLifecycleOwner, pageThumbsViewModel.pageStates) { states ->
            mPageThumbAdapter?.submitStates(states)
        }
    }

    /**
     * Snap the rating bar + emoji label back to the ViewModel's current
     * rating. Used after an in-flight PUT was rolled back (the
     * optimistic write reverted on a background thread, the UI element
     * needs to follow).
     */
    private fun rebindRatingFromViewModel() {
        val mainView = view ?: return
        val ratingBar = ViewUtils.`$$`(mainView, R.id.rating) as? RatingBar
        val ratingTextView = ViewUtils.`$$`(mainView, R.id.rating_text) as? TextView
        val current = viewModel.currentRating.value ?: 0f
        if (ratingBar != null) ratingBar.rating = current
        if (ratingTextView != null) {
            // Match DetailHeaderBinder.bindViewSecond's "Not rated"
            // fallback verbatim so a rollback does not cause the label to
            // flip styles versus the load path.
            ratingTextView.text = if (current > 0f) {
                buildRatingEmoji(current.toInt())
            } else {
                "Not rated"
            }
        }
    }

    private fun applyLocalReadingProgress(localPage0: Int) {
        // Sentinel: SP has never been written, defer to server-reported progress.
        if (localPage0 == com.hippo.ehviewer.gallery.ReadingProgressTracker.NO_LOCAL_PROGRESS) return
        val ad = viewModel.archiveDetail.value
        val archive = viewModel.archive.value
        val totalPages = ad?.archive?.pagecount ?: archive?.pagecount ?: return
        val localPage1 = localPage0 + 1
        // Local progress is authoritative once the reader has written it, so
        // sync the binder unconditionally — including going backwards. The
        // ArchiveDetail itself stays immutable; bindReadProgress is the
        // single point that owns the displayed value.
        mHeaderBinder?.bindReadProgress(localPage1, totalPages)
    }

    /**
     * Apply a [PageThumbnailsViewModel.State] snapshot to the grid UI.
     * Toggles visibility between recycler / spinner / empty-text and,
     * on Loaded transitions, primes the initial prefetch window so the
     * user sees thumbnails populating without waiting for a scroll.
     */
    private fun applyPageThumbsState(state: PageThumbnailsViewModel.State) {
        val recycler = mPageThumbRecycler ?: return
        val progress = mPageThumbProgress
        val empty = mPageThumbEmpty
        when (state) {
            is PageThumbnailsViewModel.State.Idle -> {
                recycler.visibility = View.VISIBLE
                progress?.visibility = View.GONE
                empty?.visibility = View.GONE
            }
            is PageThumbnailsViewModel.State.Loading -> {
                recycler.visibility = View.INVISIBLE
                progress?.visibility = View.VISIBLE
                empty?.visibility = View.GONE
            }
            is PageThumbnailsViewModel.State.Loaded -> {
                recycler.visibility = View.VISIBLE
                progress?.visibility = View.GONE
                empty?.visibility = View.GONE
                mPageThumbAdapter?.submitPageCount(state.pageCount)
                // The PrefetchScrollListener only fires on scroll, so the
                // first-viewport request kick comes from here. Posts to
                // the recycler so layout has applied before we calculate
                // visible rows.
                recycler.post { triggerInitialThumbPrefetch(state.pageCount) }
            }
            is PageThumbnailsViewModel.State.Error -> {
                recycler.visibility = View.GONE
                progress?.visibility = View.GONE
                if (empty != null) {
                    empty.visibility = View.VISIBLE
                    empty.text = errorTextFor(state.tier)
                }
            }
        }
    }

    private fun errorTextFor(tier: PageThumbnailsViewModel.ErrorTier): String {
        val resId = when (tier) {
            PageThumbnailsViewModel.ErrorTier.UNREACHABLE -> R.string.lrr_page_thumbnails_unreachable
            PageThumbnailsViewModel.ErrorTier.DELETED -> R.string.lrr_page_thumbnails_deleted
            PageThumbnailsViewModel.ErrorTier.GENERIC -> R.string.lrr_page_thumbnails_generic
        }
        return getString(resId)
    }

    /**
     * Request thumbnails for the initial viewport plus the standard
     * prefetch window. Repository requests are idempotent so callers
     * (this method and the scroll listener) coexist without dedup
     * logic here.
     */
    private fun triggerInitialThumbPrefetch(pageCount: Int) {
        val span = mPageThumbSpanCount.coerceAtLeast(1)
        // Visible rows ≈ recycler height / approx item height. Use a
        // fixed envelope (3 visible + 3 prefetch) instead — close
        // enough for the small grid heights we use and avoids a
        // measure-dependent calculation that would race the initial
        // layout pass.
        val initialCount = (span * INITIAL_ROWS).coerceAtMost(pageCount)
        for (page in 0 until initialCount) {
            pageThumbsViewModel.requestPage(page)
        }
    }

    /**
     * Open the reader at the given 0-indexed [page0]. Reuses the
     * shared [com.hippo.ehviewer.ui.GalleryOpenHelper] so the local /
     * stream path selection and warmup behaviour stay identical to
     * the regular "Read" button.
     */
    private fun openReaderAtPage(page0: Int) {
        val archive = viewModel.getEffectiveArchive() ?: return
        val ctx = getEHContext() ?: return
        // Tank-flow members read in the whole-tank session; the tapped
        // member-local page maps to its global page inside it.
        com.hippo.ehviewer.gallery.TankSessionRouter
            .tankIntentFor(ctx, archive.arcid, memberPage0 = page0)
            ?.let { tankIntent ->
                startActivity(tankIntent)
                return
            }
        viewLifecycleOwner.lifecycleScope.launch(
            ServiceRegistry.coroutineModule.exceptionHandler
        ) {
            val intent = withContext(Dispatchers.IO) {
                com.hippo.ehviewer.ui.GalleryOpenHelper.buildReadIntent(
                    ctx,
                    archive,
                    startPage = page0,
                )
            }
            startActivity(intent)
        }
    }

    /**
     * Prompt for a 1-indexed page number and snap the grid to that
     * page. Does not open the reader — that path is reserved for
     * thumbnail taps so the two interactions stay distinct.
     */
    private fun showJumpToPageDialog() {
        val ctx = getEHContext() ?: return
        val loaded = pageThumbsViewModel.state.value as? PageThumbnailsViewModel.State.Loaded ?: return
        val pageCount = loaded.pageCount
        if (pageCount <= 0) return

        val container = FrameLayout(ctx).apply {
            val pad = resources.getDimensionPixelSize(R.dimen.keyline_margin)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.lrr_page_thumbnails_jump_dialog_hint, pageCount)
        }
        container.addView(input)

        AlertDialog.Builder(ctx)
            .setTitle(R.string.lrr_page_thumbnails_jump_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.lrr_page_thumbnails_jump_dialog_ok) { dialog, _ ->
                val raw = input.text?.toString()?.trim()?.toIntOrNull()
                if (raw == null || raw < 1 || raw > pageCount) {
                    android.widget.Toast.makeText(
                        ctx,
                        getString(R.string.lrr_page_thumbnails_jump_dialog_out_of_range, pageCount),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    return@setPositiveButton
                }
                mPageThumbRecycler?.scrollToPosition(raw - 1)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        mSourceErrorBanner = null
        mSourceErrorText = null
        mSourceErrorRetry = null

        mActions = null
        mHeartGroup = null
        mEditTagsBtn = null

        mProgress = null
        mViewTransition2 = null

        mHeaderBinder = null
        mTankBinder = null
        mActionHandler?.destroy()
        mActionHandler = null

        // Detach the adapter explicitly so RecyclerView clears its view
        // pool — otherwise the pooled view holders keep references to
        // the previous detail page's Activity context.
        mPageThumbRecycler?.adapter = null
        mPageThumbRecycler = null
        mPageThumbProgress = null
        mPageThumbEmpty = null
        mPageThumbAdapter = null

        properties = null
    }

    private fun prepareData(): Boolean {
        val context = getEHContext()
        AssertUtils.assertNotNull(context)

        // Try ViewModel cache (checks in-memory detail, then archiveDetailCache)
        if (!viewModel.tryLoadFromCache()) {
            return false
        }
        if (viewModel.archiveDetail.value != null) {
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
        val viewTransition = mViewTransition ?: return
        val viewTransition2 = mViewTransition2 ?: return

        val oldState = mState
        mState = state

        @Suppress("NAME_SHADOWING")
        val animation = !TRANSITION_ANIMATION_DISABLED && animation

        when (state) {
            STATE_NORMAL -> {
                // Show mMainView
                viewTransition.showView(0, animation)
                // Show mBelowHeader
                viewTransition2.showView(0, animation)
            }
            STATE_REFRESH -> {
                // Show mProgressView
                viewTransition.showView(1, animation)
            }
            STATE_REFRESH_HEADER -> {
                // Show mMainView
                viewTransition.showView(0, animation)
                // Show mProgress
                viewTransition2.showView(1, animation)
            }
            else -> {
                // STATE_INIT, STATE_FAILED
                // Show mFailedView
                viewTransition.showView(2, animation)
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
        mContext = ctx
        activity = activity2
        if (activity == null) {
            return
        }

        if (mTip === v) {
            if (request()) {
                adjustViewVisibility(STATE_REFRESH, true)
            }
        } else {
            mActionHandler?.onClick(v, ctx, activity)
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


    private fun openTankoubonDetail(tank: LRRTankoubonApi.Tankoubon) {
        val args = Bundle().apply {
            putString(TankoubonDetailScene.KEY_TANK_ID, tank.id)
            putString(TankoubonDetailScene.KEY_TANK_NAME, tank.name)
            putLong(TankoubonDetailScene.KEY_PROFILE_ID, viewModel.getSourceProfileId())
        }
        startScene(Announcer(TankoubonDetailScene::class.java).setArgs(args))
    }

    private fun showTankMembershipDialog() {
        val arcid = viewModel.getEffectiveArcid() ?: return
        TankoubonDialogHelper.showMembershipDialog(
            activity2, arcid, viewModel.getSourceProfileId()
        ) { viewModel.loadArchiveTankoubons() }
    }

    override fun onBackPressed() {
        val current = viewModel.currentRating.value
        val arcid = viewModel.getEffectiveArcid()
        if (current != null && arcid != null && !mInitialRating.isNaN() && current != mInitialRating) {
            val data = android.os.Bundle()
            data.putString(KEY_ARCID, arcid)
            data.putFloat(KEY_RATING_RESULT, current)
            setResult(RESULT_OK, data)
        }
        finish()
    }


    internal fun onGetArchiveDetailSuccess(result: ArchiveDetail) {
        try {
            onGetArchiveDetailSuccessInternal(result)
        } catch (e: Exception) {
            android.util.Log.e("GalleryDetailScene", "onGetArchiveDetailSuccess crashed", e)
        }
    }

    private fun onGetArchiveDetailSuccessInternal(result: ArchiveDetail) {
        if (mInitialRating.isNaN()) mInitialRating = result.archive.rating
        viewModel.refreshDownloadState()
        val dlState = viewModel.downloadState.value
        if (dlState != DownloadState.INVALID) {
            // Refresh the cached download row's thumb if the freshly fetched
            // detail carries a different one. Snapshot lookup — the row is
            // only read here, so we do not need a long-lived StateFlow.
            val di = ServiceRegistry.dataModule.downloadManager.getDownloadInfo(result.archive.arcid)
            if (di != null && di.thumb != null &&
                di.thumb != result.archive.thumbnailUrl && di.arcid == result.archive.arcid
            ) {
                mHeaderBinder?.useNetWorkLoadThumb = true
                di.updateInfo(result.archive)
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
        val tip = mTip
        if (context != null && tip != null) {
            val error = ExceptionUtils.getReadableString(e)
            tip.text = error
            adjustViewVisibility(STATE_FAILED, true)
        }
    }

    /**
     * Show the cache-first error banner above the detail content. The
     * page is already rendered (LRU detail cache hit, or a degraded
     * detail seeded from the navigation Archive) so we keep the user
     * on STATE_NORMAL and surface the failure as a dismissible bar
     * with a Retry button. A subsequent successful load auto-hides it
     * via the [GalleryDetailViewModel.detailLoaded] observer.
     */
    private fun showSourceErrorBanner(e: Exception) {
        val ctx = getEHContext() ?: return
        val banner = mSourceErrorBanner ?: return
        val text = mSourceErrorText ?: return
        text.text = tieredSourceErrorText(ctx, e)
        banner.visibility = View.VISIBLE
        // The banner is informational on top of cached data — make sure
        // the page itself stays in NORMAL so the cached content remains
        // interactive (cancel buttons, read button, etc.).
        if (mState != STATE_NORMAL) {
            adjustViewVisibility(STATE_NORMAL, true)
        }
    }

    /**
     * Map a fetch failure to a tiered, user-actionable string. The four
     * tiers (orphan / unreachable / 404 / generic) match the cross-
     * server UX patterns established by Plex / email clients / RSS
     * readers — distinguishing why the source is unavailable lets the
     * user decide whether to retry, switch profiles, or give up.
     *
     * Profile name is looked up against the same [ProfileLookupCache]
     * the auth interceptor uses, so the banner shows the user-chosen
     * server label rather than a raw URL.
     */
    private fun tieredSourceErrorText(ctx: android.content.Context, e: Exception): String {
        val sid = viewModel.getEffectiveArchive()?.serverProfileId ?: 0L
        // Profile name comes from the same in-memory cache the auth
        // interceptor consults, so the banner mirrors what the user
        // sees in ServerListScene. If the lookup misses (orphan path
        // is handled below; this guards against the rare race where
        // the profile is deleted between request fire and response)
        // the placeholder collapses to empty rather than crashing the
        // resource formatter — the message still reads correctly.
        val profileName = ServiceRegistry.dataModule.profileLookupCache
            .findById(sid)?.name ?: ""
        return when {
            e is com.lanraragi.reader.client.api.OrphanProfileException ->
                ctx.getString(R.string.lrr_detail_source_profile_orphan)
            // LANraragi returns HTTP 400 ("Invalid id") for unknown
            // arcids on /api/archives/{arcid}/metadata — semantically
            // equivalent to the archive having been deleted server-side.
            // 404 is also handled for forward-compat with Shinobu / future
            // servers that follow the more conventional REST mapping.
            e is com.lanraragi.reader.client.api.LRRHttpException &&
                (e.code == 400 || e.code == 404) ->
                ctx.getString(R.string.lrr_detail_source_archive_deleted, profileName)
            e is java.net.UnknownHostException ||
                e is java.net.SocketTimeoutException ||
                e is java.net.ConnectException ->
                ctx.getString(R.string.lrr_detail_source_unreachable, profileName)
            else -> com.lanraragi.reader.client.api.friendlyError(ctx, e)
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
        const val ACTION_ARCHIVE = "action_archive"

        const val KEY_ARCHIVE = "archive"
        const val KEY_ARCID = "token"
        const val KEY_PAGE = "page"
        const val KEY_RATING_RESULT = "rating_result"

        private const val KEY_ARCHIVE_DETAIL = "archive_detail"
        private const val KEY_REQUEST_ID = "request_id"

        // Thumbnail grid column count is derived from screen width:
        // floor(screen_width / target_tile_width), clamped here so a
        // foldable / tablet never blows past 6 columns and a narrow
        // phone never collapses below 3. The target tile width is
        // resolved per-bind from AppearanceSettings.getDetailPageThumb
        // SizeResId() so the user's small/medium/large preference
        // takes effect on next Scene entry.
        private const val SPAN_MIN: Int = 3
        private const val SPAN_MAX: Int = 6

        // Initial request window: span_count × this many rows.
        // 4 rows cover the typical visible viewport + one row of
        // prefetch headroom; the scroll listener picks up the rest.
        private const val INITIAL_ROWS: Int = 4

        private const val TRANSITION_ANIMATION_DISABLED = true
    }
}
