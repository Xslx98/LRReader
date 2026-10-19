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
import com.lanraragi.reader.event.AppEventBus
import com.hippo.android.resource.AttrResources
import com.hippo.drawerlayout.DrawerLayout
import com.lanraragi.reader.Analytics
import com.lanraragi.reader.LRReaderApplication
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.settings.AppearanceSettings
import androidx.lifecycle.ViewModelProvider
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.domain.ArchiveDetail
import com.lanraragi.reader.ui.MainActivity
import com.lanraragi.reader.ui.scene.BaseScene
import com.lanraragi.reader.ui.scene.TankoubonDetailScene
import com.lanraragi.reader.util.collectFlow
import com.lanraragi.reader.util.collectFlowWhileCreated
import com.lanraragi.framework.scene.Announcer
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.widget.ArchiverDownloadProgress
import com.lanraragi.framework.lib.yorozuya.AssertUtils
import com.lanraragi.framework.lib.yorozuya.IntIdGenerator
import com.lanraragi.framework.lib.yorozuya.ViewUtils
import com.hippo.ripple.Ripple
import com.lanraragi.framework.util.DrawableManager
import com.lanraragi.framework.util.ExceptionUtils
import kotlinx.coroutines.launch
import com.lanraragi.framework.view.ViewTransition
import com.lanraragi.framework.widget.LoadImageView
import java.text.SimpleDateFormat
import java.util.Date
import com.lanraragi.reader.download.DownloadState

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
            keepPageNormal = { if (mState != STATE_NORMAL) adjustViewVisibility(STATE_NORMAL, true) },
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
                // Opening the detail page is browsing, not reading: no history
                // row here. The reader records history when a session starts
                // (audit 2026-10-04, user ruling extending C03).
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
        pageThumbs = DetailPageThumbs(this, viewModel, pageThumbsViewModel)

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
        mSourceError.bind(belowHeader)
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

        mRating.bind(rating, ratingText)

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

        pageThumbs.setUp(view)

        return view
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
            mSourceError.hide()
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
            mSourceError.show(e)
        }
        // After a rollback the optimistic write to currentRating /
        // archiveDetail has been reverted on the IO thread; reflect the
        // restored value on the rating bar and toast the error so the
        // user knows their change did not stick.
        // collectFlowWhileCreated: losing the event would leave the rating
        // bar showing the rolled-back optimistic value.
        collectFlowWhileCreated(viewLifecycleOwner, viewModel.ratingError) { e ->
            mRating.onRatingError(view, getEHContext(), e)
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
        // The reader (a separate activity on top) sets the cover while this
        // scene is STOPPED. A replay-0 SharedFlow drops emissions that have
        // no live subscriber, so collect for the whole view lifetime (the
        // view survives under another activity); the reload is a cache-key
        // change plus a fetch, harmless while stopped.
        collectFlowWhileCreated(viewLifecycleOwner, AppEventBus.archiveCoverChangedEvent) { event ->
            val archive = viewModel.archive.value ?: return@collectFlowWhileCreated
            if (archive.arcid == event.arcid) mHeaderBinder?.reloadCover(archive)
        }

        // ----- Page-thumbnail grid wiring -----
        pageThumbs.observe(viewLifecycleOwner)
    }

    private fun applyLocalReadingProgress(localPage0: Int) {
        // Sentinel: SP has never been written, defer to server-reported progress.
        if (localPage0 == com.lanraragi.reader.gallery.ReadingProgressTracker.NO_LOCAL_PROGRESS) return
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

    override fun onDestroyView() {
        super.onDestroyView()
        // Leaving the page (not a rotation, not the reader opening — that is
        // another activity) ends its speculative reading preload.
        if (activity?.isChangingConfigurations != true) viewModel.cancelReadingPreload()

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

        val application = requireContext().applicationContext as LRReaderApplication
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
            activity2, arcid, viewModel.getSourceProfileId(),
            archiveTitle = viewModel.getEffectiveArchive()?.title,
        ) { viewModel.loadArchiveTankoubons() }
    }


    internal fun onGetArchiveDetailSuccess(result: ArchiveDetail) {
        try {
            onGetArchiveDetailSuccessInternal(result)
        } catch (e: Exception) {
            android.util.Log.e("GalleryDetailScene", "onGetArchiveDetailSuccess crashed", e)
        }
    }

    private fun onGetArchiveDetailSuccessInternal(result: ArchiveDetail) {
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
                // The download state is owned by the scheduler: it is not
                // copied back here (it could only be stale or another
                // archive's), and the row is written through the ordered
                // download write queue (audit 2026-10-06b STAB-01).
                viewModel.updateDownloadRowFromDetail(result.archive)
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

        private const val KEY_ARCHIVE_DETAIL = "archive_detail"
        private const val KEY_REQUEST_ID = "request_id"

        private const val TRANSITION_ANIMATION_DISABLED = true
    }
}
