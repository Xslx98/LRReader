package com.lanraragi.reader.ui.scene.tankdetail

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hippo.android.resource.AttrResources
import com.hippo.ripple.Ripple
import com.lanraragi.framework.lib.yorozuya.ViewUtils
import com.lanraragi.framework.scene.Announcer
import com.lanraragi.framework.util.DrawableManager
import com.lanraragi.framework.view.ViewTransition
import com.lanraragi.framework.widget.LoadImageView
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.ArchiveCoverStamps
import com.lanraragi.reader.client.LRRCacheKeyFactory
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.buildRatingEmoji
import com.lanraragi.reader.download.DownloadState
import com.lanraragi.reader.download.TankFillDispatcher
import com.lanraragi.reader.gallery.GalleryProvider2
import com.lanraragi.reader.gallery.TankMemberSeed
import com.lanraragi.reader.gallery.TankPageMath
import com.lanraragi.reader.gallery.TankSeedStore
import com.lanraragi.reader.gallery.TankSessionSeed
import com.lanraragi.reader.settings.AppearanceSettings
import com.lanraragi.reader.tankoubon.TankMemberStrip
import com.lanraragi.reader.ui.GalleryOpenHelper
import com.lanraragi.reader.ui.scene.BaseScene
import com.lanraragi.reader.ui.scene.TankoubonDetailScene
import com.lanraragi.reader.client.data.ListUrlBuilder
import com.lanraragi.reader.ui.scene.gallery.detail.CategoryDialogHelper
import com.lanraragi.reader.ui.scene.gallery.detail.FavoriteState
import com.lanraragi.reader.ui.scene.gallery.detail.GalleryDetailScene
import com.lanraragi.reader.ui.scene.gallery.detail.GalleryTagHelper
import com.lanraragi.reader.ui.scene.gallery.detail.PrefetchScrollListener
import com.lanraragi.reader.ui.scene.gallery.detail.TagEditDialog
import com.lanraragi.reader.ui.scene.gallery.list.GalleryListScene
import com.lanraragi.reader.ui.scene.tankdetail.TankDetailViewModel.LoadState
import com.lanraragi.reader.ui.scene.tankdetail.TankDetailViewModel.TankDetailState
import com.lanraragi.reader.ui.widget.bindSourceServerBadge
import com.lanraragi.reader.util.collectFlow
import com.lanraragi.reader.util.collectFlowWhileCreated
import kotlin.math.ceil

/**
 * Tankoubon detail page (spec 2026-09-22 §4): a tank rendered like a
 * single archive's detail — cover, name, pages, rating, category heart,
 * tags, members, page previews — on the `gallery_detail_*` includes.
 *
 * Read = whole-tank composite session (provider-restored progress);
 * Download = the shared [TankFillDispatcher] fill path. Member
 * management stays in [com.lanraragi.reader.ui.scene.TankoubonDetailScene].
 *
 * The ViewModel is SCENE-scoped: the scene is LAUNCH_MODE_STANDARD, so
 * two stacked detail pages must not share state.
 */
class TankDetailScene : BaseScene(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var viewModel: TankDetailViewModel

    private var mTagsLayout: LinearLayout? = null
    private var mNoTags: TextView? = null
    private var mEditTagsBtn: View? = null

    private var mMembersRecycler: RecyclerView? = null
    private var mMembersAdapter: TankMemberStripAdapter? = null
    private var mMembersManage: View? = null

    private lateinit var pageThumbsViewModel: TankPageThumbnailsViewModel
    private var mPreviews: View? = null
    private var mPageGrid: RecyclerView? = null
    private var mPageGridAdapter: TankPageGridAdapter? = null
    private var mPageGridEmpty: TextView? = null
    private var mPageGridSpanCount: Int = SPAN_MIN

    private var mViewTransition: ViewTransition? = null
    private var mViewTransition2: ViewTransition? = null
    private var mTip: TextView? = null
    private var mBelowHeader: View? = null
    private var mOfflineBanner: View? = null
    private var mOfflineText: TextView? = null
    private var mOfflineRetry: View? = null

    private var mThumb: LoadImageView? = null
    private var mTitle: TextView? = null
    private var mSourceBadge: TextView? = null
    private var mColorBg: View? = null
    private var mDownload: TextView? = null
    private var mRead: View? = null
    private var mPages: TextView? = null
    private var mSize: TextView? = null
    private var mRatingText: TextView? = null
    private var mRating: RatingBar? = null
    private var mHeartGroup: View? = null
    private var mHeart: TextView? = null
    private var mHeartOutline: TextView? = null

    private var mCoverBinding: String? = null
    private var mRevealed = false

    override fun onCreateView2(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val view = inflater.inflate(R.layout.scene_tank_detail, container, false)
        val context = requireContext()

        val tankId = arguments?.getString(KEY_TANK_ID)
        if (tankId.isNullOrEmpty()) {
            Toast.makeText(context, R.string.error_unknown, Toast.LENGTH_SHORT).show()
            view.post { onBackPressed() }
            return view
        }
        viewModel = ViewModelProvider(this)[TankDetailViewModel::class.java]
        viewModel.init(
            tankId = tankId,
            name = arguments?.getString(KEY_TANK_NAME).orEmpty(),
            profileId = arguments?.getLong(KEY_PROFILE_ID) ?: 0L,
        )

        val main = ViewUtils.`$$`(view, R.id.main) as ViewGroup
        val mainView = ViewUtils.`$$`(main, R.id.scroll_view)
        val progressView = ViewUtils.`$$`(main, R.id.progress_view)
        val tip = ViewUtils.`$$`(main, R.id.tip) as TextView
        mTip = tip
        mViewTransition = ViewTransition(mainView, progressView, tip)
        DrawableManager.getVectorDrawable(context, R.drawable.big_sad_pandroid)?.let { drawable ->
            drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
            tip.setCompoundDrawables(null, drawable, null, null)
        }
        tip.setOnClickListener(this)

        val belowHeader: View = mainView.findViewById(R.id.below_header)
        mBelowHeader = belowHeader
        mOfflineBanner = belowHeader.findViewById(R.id.source_error_banner)
        mOfflineText = belowHeader.findViewById(R.id.source_error_text)
        mOfflineRetry = belowHeader.findViewById(R.id.source_error_retry)
        mOfflineRetry?.setOnClickListener { reload() }

        val isDarkTheme = !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme)
        val header = ViewUtils.`$$`(belowHeader, R.id.header)
        mColorBg = ViewUtils.`$$`(header, R.id.color_bg)
        mThumb = ViewUtils.`$$`(header, R.id.thumb) as LoadImageView
        mTitle = ViewUtils.`$$`(header, R.id.title) as TextView
        // Tanks have no uploader; keep the slot collapsed.
        (ViewUtils.`$$`(header, R.id.uploader) as TextView).visibility = View.GONE
        mSourceBadge = ViewUtils.`$$`(header, R.id.source_badge) as TextView
        // Overflow (manage members / delete) lands in a later step.
        (ViewUtils.`$$`(header, R.id.other_actions) as ImageView).visibility = View.GONE
        val actionCard = ViewUtils.`$$`(header, R.id.action_card) as ViewGroup
        val download = ViewUtils.`$$`(actionCard, R.id.download) as TextView
        val read = ViewUtils.`$$`(actionCard, R.id.read)
        mDownload = download
        mRead = read
        Ripple.addRipple(download, isDarkTheme)
        Ripple.addRipple(read, isDarkTheme)
        download.setOnClickListener(this)
        read.setOnClickListener(this)

        val infoView = ViewUtils.`$$`(belowHeader, R.id.info)
        mPages = ViewUtils.`$$`(infoView, R.id.pages) as TextView
        mSize = ViewUtils.`$$`(infoView, R.id.size) as TextView

        val actions = ViewUtils.`$$`(belowHeader, R.id.actions)
        mRatingText = ViewUtils.`$$`(actions, R.id.rating_text) as TextView
        mRating = ViewUtils.`$$`(actions, R.id.rating) as RatingBar
        val heartGroup = ViewUtils.`$$`(actions, R.id.heart_group)
        mHeartGroup = heartGroup
        Ripple.addRipple(heartGroup, isDarkTheme)
        heartGroup.setOnClickListener(this)
        mHeart = ViewUtils.`$$`(heartGroup, R.id.heart) as TextView
        mHeartOutline = ViewUtils.`$$`(heartGroup, R.id.heart_outline) as TextView
        ensureHeartDrawables()
        setupRatingBar()

        val tagsLayout = ViewUtils.`$$`(belowHeader, R.id.tags) as LinearLayout
        mTagsLayout = tagsLayout
        mNoTags = ViewUtils.`$$`(tagsLayout, R.id.no_tags) as TextView
        mEditTagsBtn = ViewUtils.`$$`(tagsLayout, R.id.edit_tags_btn).also { it.setOnClickListener(this) }

        val membersSection = ViewUtils.`$$`(belowHeader, R.id.tank_members)
        mMembersManage = ViewUtils.`$$`(membersSection, R.id.tank_members_manage).also { it.setOnClickListener(this) }
        val membersAdapter = TankMemberStripAdapter(
            onMemberClick = { openMemberSession(it) },
            onMemberLongClick = { member, anchor -> showMemberMenu(member, anchor) },
            onMoreClick = { openMemberManagement() },
        )
        mMembersAdapter = membersAdapter
        mMembersRecycler = (ViewUtils.`$$`(membersSection, R.id.tank_members_recycler) as RecyclerView).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = membersAdapter
        }

        pageThumbsViewModel = ViewModelProvider(this)[TankPageThumbnailsViewModel::class.java]
        setupPageGrid(ViewUtils.`$$`(belowHeader, R.id.previews))

        val progress = ViewUtils.`$$`(mainView, R.id.progress)
        mViewTransition2 = ViewTransition(belowHeader, progress)

        // Instant header from the nav arg while the fetch runs.
        mTitle?.text = viewModel.seedName
        bindSourceBadge()

        observeViewModel()
        if (viewModel.loadState.value is LoadState.Idle) {
            viewModel.load()
        }
        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        GalleryTagHelper.destroy()
        mTagsLayout = null
        mNoTags = null
        mEditTagsBtn = null
        // Detach so the pool drops holders referencing this view tree.
        mMembersRecycler?.adapter = null
        mMembersRecycler = null
        mMembersAdapter = null
        mMembersManage = null
        mPageGrid?.adapter = null
        mPageGrid = null
        mPageGridAdapter = null
        mPageGridEmpty = null
        mPreviews = null
        mViewTransition = null
        mViewTransition2 = null
        mTip = null
        mBelowHeader = null
        mOfflineBanner = null
        mOfflineText = null
        mOfflineRetry = null
        mThumb = null
        mTitle = null
        mSourceBadge = null
        mColorBg = null
        mDownload = null
        mRead = null
        mPages = null
        mSize = null
        mRatingText = null
        mRating = null
        mHeartGroup = null
        mHeart = null
        mHeartOutline = null
        mCoverBinding = null
    }

    override fun onResume() {
        super.onResume()
        // The reader may have changed the cover (stamp bumped) or the
        // downloads list may have changed member states while we were away.
        viewModel.state.value?.let {
            bindCover(it)
            bindMembers(it)
        }
        updateDownloadText()
    }

    // -------------------------------------------------------------------------
    // Observation
    // -------------------------------------------------------------------------

    private fun observeViewModel() {
        collectFlow(viewLifecycleOwner, viewModel.loadState) { state ->
            when (state) {
                LoadState.Idle, LoadState.Loading -> showLoading()
                LoadState.Loaded -> showContent()
                LoadState.Unsupported -> showFailed(getString(R.string.tankoubons_unsupported))
                is LoadState.Error -> showFailed(state.message)
            }
        }
        collectFlow(viewLifecycleOwner, viewModel.state) { state ->
            if (state != null) bindState(state)
        }
        collectFlow(viewLifecycleOwner, viewModel.favoriteState) { bindHeart() }
        // Rolled back already; re-render from the VM and tell the user.
        collectFlowWhileCreated(viewLifecycleOwner, viewModel.ratingError) { message ->
            viewModel.state.value?.let { bindRating(it.rating) }
            ehContext?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
        }
    }

    /**
     * Hands a changed rating back to the launching list (same contract as
     * the archive page: [GalleryDetailScene.KEY_ARCID] +
     * [GalleryDetailScene.KEY_RATING_RESULT]) so the folded tank row's
     * stars update without a refresh.
     */
    override fun onBackPressed() {
        val s = viewModel.state.value
        val initial = viewModel.initialRating
        if (s != null && !initial.isNaN() && s.rating.coerceAtLeast(0f) != initial) {
            val data = Bundle()
            data.putString(GalleryDetailScene.KEY_ARCID, s.tankId)
            data.putFloat(GalleryDetailScene.KEY_RATING_RESULT, s.rating.coerceAtLeast(0f))
            setResult(RESULT_OK, data)
        }
        finish()
    }

    private fun showLoading() {
        // Keep the seeded header visible while a reload runs after a first
        // success; a cold load shows the full-page spinner.
        if (viewModel.state.value != null) {
            mViewTransition?.showView(0, false)
            mViewTransition2?.showView(1, false)
        } else {
            mViewTransition?.showView(1, false)
        }
    }

    private fun showContent() {
        mViewTransition?.showView(0, false)
        mViewTransition2?.showView(0, false)
        val ctx = ehContext ?: return
        if (!mRevealed && AttrResources.getAttrBoolean(ctx, androidx.appcompat.R.attr.isLightTheme)) {
            mRevealed = true
            mColorBg?.post { createCircularReveal() }
        }
    }

    private fun showFailed(message: String) {
        mTip?.text = message
        mViewTransition?.showView(2, false)
    }

    private fun reload() {
        mOfflineBanner?.visibility = View.GONE
        viewModel.load()
    }

    // -------------------------------------------------------------------------
    // Binding
    // -------------------------------------------------------------------------

    private fun bindState(s: TankDetailState) {
        mTitle?.text = s.name
        bindSourceBadge()
        bindCover(s)
        val displayProgress = if (s.progress > 0) s.progress else 1
        mPages?.text = "$displayProgress/${s.totalPages}P"
        mSize?.text = resources.getQuantityString(R.plurals.lrr_category_archives, s.memberCount, s.memberCount)
        bindRating(s.rating)
        bindHeart()
        bindTags(s)
        bindMembers(s)
        bindPreviews(s)
        updateDownloadText()
        val banner = mOfflineBanner ?: return
        if (s.offline) {
            mOfflineText?.text = getString(R.string.tank_detail_offline)
            banner.visibility = View.VISIBLE
        } else {
            banner.visibility = View.GONE
        }
    }

    /**
     * Cover = the tank thumbnail route stamped by [ArchiveCoverStamps]
     * (TANK_ ids answer the process-wide stamp), or the first member when
     * the server reported no generated cover yet, or — offline — the
     * first snapshot member. Short-circuits when the binding is unchanged.
     */
    private fun bindCover(s: TankDetailState) {
        val thumb = mThumb ?: return
        val key: String
        val url: String
        val fallback = s.coverFallbackMember ?: s.members.firstOrNull().takeIf { s.baseUrl == null || s.offline }
        if (fallback != null) {
            key = LRRCacheKeyFactory.getThumbKey(fallback.arcid)
            url = ArchiveCoverStamps.bust(fallback.thumbnailUrl, fallback.arcid)
        } else {
            val base = s.baseUrl ?: return
            key = LRRCacheKeyFactory.getThumbKey(s.tankId)
            url = ArchiveCoverStamps.bust(LRRTankoubonApi.getTankoubonThumbnailUrl(base, s.tankId), s.tankId)
        }
        val binding = "$key|$url"
        if (binding == mCoverBinding) return
        mCoverBinding = binding
        thumb.load(key, url)
    }

    /**
     * Interactive rating, same gesture contract as the archive page:
     * stepSize 0.5 for smooth drag feedback, the value ceiled to whole
     * stars on release (read on the next frame — dispatchTouchEvent runs
     * the listener BEFORE RatingBar applies the UP coordinates), then one
     * business entry point: [TankDetailViewModel.submitRating]. Read-only
     * while offline.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupRatingBar() {
        val rating = mRating ?: return
        rating.setIsIndicator(false)
        rating.stepSize = RATING_STEP
        rating.onRatingBarChangeListener = RatingBar.OnRatingBarChangeListener { _, value, fromUser ->
            if (!fromUser || viewModel.state.value == null) return@OnRatingBarChangeListener
            mRatingText?.text = buildRatingEmoji(ceil(value).toInt())
        }
        rating.setOnTouchListener { _, event ->
            val s = viewModel.state.value
            if (s == null || s.offline) return@setOnTouchListener true
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                rating.post {
                    if (viewModel.state.value == null) return@post
                    val finalRating = ceil(rating.rating).coerceIn(0f, MAX_STARS)
                    bindRating(finalRating)
                    viewModel.submitRating(finalRating)
                }
            }
            false
        }
    }

    private fun bindRating(rating: Float) {
        if (rating > 0f) {
            mRatingText?.text = String.format("%.0f★", rating)
            mRating?.rating = rating
        } else {
            mRatingText?.setText(R.string.not_rated)
            mRating?.rating = 0f
        }
    }

    private fun bindHeart() {
        val fav = viewModel.favoriteState.value
        val heart = mHeart ?: return
        val outline = mHeartOutline ?: return
        if (fav?.isFavorited == true) {
            heart.visibility = View.VISIBLE
            heart.text = fav.name ?: getString(R.string.favorited)
            outline.visibility = View.GONE
        } else {
            heart.visibility = View.GONE
            outline.visibility = View.VISIBLE
        }
    }

    /** Badge only for a tank whose source profile is not the active one, as for archives. */
    private fun bindSourceBadge() {
        val badge = mSourceBadge ?: return
        val sid = viewModel.profileId
        if (sid != 0L && sid != LRRAuthManager.getActiveProfileId()) {
            bindSourceServerBadge(badge, sid)
        } else {
            badge.visibility = View.GONE
        }
    }

    private fun ensureHeartDrawables() {
        val ctx = ehContext ?: return
        DrawableManager.getVectorDrawable(ctx, R.drawable.v_heart_primary_x48)?.let { setActionDrawable(mHeart, it) }
        DrawableManager.getVectorDrawable(ctx, R.drawable.v_heart_outline_primary_x48)?.let {
            setActionDrawable(mHeartOutline, it)
        }
    }

    private fun setActionDrawable(text: TextView?, drawable: android.graphics.drawable.Drawable) {
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        text?.setCompoundDrawables(null, drawable, null, null)
    }

    private fun createCircularReveal() {
        val bg = mColorBg ?: return
        val w = bg.width
        val h = bg.height
        if (!bg.isAttachedToWindow || w == 0 || h == 0) return
        val res = bg.context.resources
        val keyline = res.getDimensionPixelSize(R.dimen.keyline_margin)
        val x = res.getDimensionPixelSize(R.dimen.gallery_detail_thumb_width) / 2 + keyline
        val y = res.getDimensionPixelSize(R.dimen.gallery_detail_thumb_height) / 2 + keyline
        val radiusX = maxOf(x, w - x)
        val radiusY = maxOf(y, h - y)
        val radius = Math.hypot(radiusX.toDouble(), radiusY.toDouble()).toFloat()
        com.lanraragi.framework.reveal.ViewAnimationUtils.createCircularReveal(bg, x, y, 0f, radius)
            .setDuration(REVEAL_DURATION_MS).start()
    }

    // -------------------------------------------------------------------------
    // Members strip (spec 2026-09-22 §4.6)
    // -------------------------------------------------------------------------

    /**
     * Greyed = missing members of a DOWNLOADED tank (the downloads card's
     * INCOMPLETE state seen per member); a tank that is not downloaded
     * greys nothing. DownloadManager lookups are main-thread only — this
     * runs from the state collector on Main.
     */
    private fun bindMembers(s: TankDetailState) {
        val dm = ServiceRegistry.dataModule.downloadManager
        val tankDownloaded = s.members.any { dm.tankGroupFor(it.arcid)?.tankId == s.tankId }
        val greyed = s.members
            .filter { TankMemberStrip.isGreyed(tankDownloaded, dm.getDownloadState(it.arcid)) }
            .mapTo(HashSet()) { it.arcid }
        mMembersAdapter?.submit(s.members, greyed)
        // Management writes to the source server — no server offline.
        mMembersManage?.visibility = if (s.offline) View.GONE else View.VISIBLE
    }

    /**
     * Member tap (v1.24.0 sealing rule): the WHOLE-TANK session positioned
     * on that member — saved tank progress inside it restores, otherwise
     * its first page.
     */
    private fun openMemberSession(member: Archive) {
        val ctx = ehContext ?: return
        val s = viewModel.state.value ?: return
        val index = s.members.indexOfFirst { it.arcid == member.arcid }
        if (index < 0) return
        val start = TankPageMath.anchoredStart(
            s.members.map { it.pagecount },
            index,
            GalleryProvider2.loadReadingProgress(ctx, s.tankId),
        )
        openTankSession(startGlobalPage = start)
    }

    /** Long-press: the only door to a member's own detail page (needed to edit its tags). */
    private fun showMemberMenu(member: Archive, anchor: View) {
        val ctx = ehContext ?: return
        PopupMenu(ctx, anchor).apply {
            menu.add(R.string.tank_member_view_detail).setOnMenuItemClickListener {
                openMemberDetail(member)
                true
            }
            show()
        }
    }

    private fun openMemberDetail(member: Archive) {
        val args = Bundle()
        args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_ARCHIVE)
        args.putParcelable(GalleryDetailScene.KEY_ARCHIVE, member)
        startScene(Announcer(GalleryDetailScene::class.java).setArgs(args))
    }

    /** 「管理成员」 / "+N": the member-management sub-page (rename / reorder / remove / cover). */
    private fun openMemberManagement() {
        val s = viewModel.state.value ?: return
        val args = Bundle()
        args.putString(TankoubonDetailScene.KEY_TANK_ID, s.tankId)
        args.putString(TankoubonDetailScene.KEY_TANK_NAME, s.name)
        args.putLong(TankoubonDetailScene.KEY_PROFILE_ID, s.profileId)
        startScene(Announcer(TankoubonDetailScene::class.java).setArgs(args))
    }

    // -------------------------------------------------------------------------
    // Page previews (spec 2026-09-22 §4.7): continuous global grid + member dividers
    // -------------------------------------------------------------------------

    private fun setupPageGrid(previews: View) {
        mPreviews = previews
        val recycler = previews.findViewById<RecyclerView>(R.id.page_thumb_recycler) ?: return
        mPageGrid = recycler
        mPageGridEmpty = previews.findViewById(R.id.page_thumb_empty)
        previews.findViewById<View>(R.id.page_thumb_progress)?.visibility = View.GONE

        val targetWidthPx = resources.getDimensionPixelSize(AppearanceSettings.getDetailPageThumbSizeResId())
            .coerceAtLeast(1)
        val spanCount = (resources.displayMetrics.widthPixels / targetWidthPx).coerceIn(SPAN_MIN, SPAN_MAX)
        mPageGridSpanCount = spanCount

        val adapter = TankPageGridAdapter(
            onPageClick = { global0 -> openTankSession(startGlobalPage = global0) },
            onPageRetry = { global0 -> pageThumbsViewModel.retryPage(global0) },
        )
        mPageGridAdapter = adapter
        recycler.layoutManager = GridLayoutManager(previews.context, spanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = adapter.spanSize(position, spanCount)
            }
        }
        // No item-change animation: the payload rebind must not flash the tile.
        recycler.itemAnimator = null
        recycler.adapter = adapter
        recycler.addOnScrollListener(
            PrefetchScrollListener(spanCount) { position ->
                adapter.globalAt(position)?.let { pageThumbsViewModel.requestPage(it) }
            }
        )
        previews.findViewById<View>(R.id.page_thumb_jump)?.setOnClickListener { showJumpToPageDialog() }

        collectFlow(viewLifecycleOwner, pageThumbsViewModel.layout) { layout ->
            if (layout == null) return@collectFlow
            val s = viewModel.state.value ?: return@collectFlow
            adapter.submit(layout, s.members)
            val empty = mPageGridEmpty
            if (layout.totalPages == 0) {
                empty?.setText(R.string.lrr_page_thumbnails_generic)
                empty?.visibility = View.VISIBLE
            } else {
                empty?.visibility = View.GONE
            }
            // The scroll listener only fires on scroll; kick the first viewport
            // after layout so the positions are known.
            recycler.post { triggerInitialPagePrefetch(layout.size) }
        }
        collectFlow(viewLifecycleOwner, pageThumbsViewModel.pageStates) { adapter.submitStates(it) }
    }

    /** Thumbnails need the source server: hidden offline, otherwise (re)started on the loaded members. */
    private fun bindPreviews(s: TankDetailState) {
        val previews = mPreviews ?: return
        val base = s.baseUrl
        if (s.offline || base == null) {
            previews.visibility = View.GONE
            return
        }
        previews.visibility = View.VISIBLE
        pageThumbsViewModel.start(s.members, base)
    }

    private fun triggerInitialPagePrefetch(itemCount: Int) {
        val adapter = mPageGridAdapter ?: return
        val initial = (mPageGridSpanCount.coerceAtLeast(1) * INITIAL_ROWS).coerceAtMost(itemCount)
        for (position in 0 until initial) {
            adapter.globalAt(position)?.let { pageThumbsViewModel.requestPage(it) }
        }
    }

    /** Jump = scroll the grid to a 1-indexed GLOBAL page; taps open the reader, this never does. */
    private fun showJumpToPageDialog() {
        val ctx = ehContext ?: return
        val layout = pageThumbsViewModel.layout.value ?: return
        val pageCount = layout.totalPages
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
                    Toast.makeText(
                        ctx,
                        getString(R.string.lrr_page_thumbnails_jump_dialog_out_of_range, pageCount),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setPositiveButton
                }
                mPageGrid?.scrollToPosition(layout.positionOfGlobal(raw - 1))
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // -------------------------------------------------------------------------
    // Download card
    // -------------------------------------------------------------------------

    /**
     * Aggregate member state → button text: all downloaded → Downloaded;
     * any in flight → Downloading; any failed (none in flight) → Failed;
     * otherwise Download. Mirrors the downloads-card aggregate.
     */
    private fun updateDownloadText() {
        val dl = mDownload ?: return
        val members = viewModel.state.value?.members
        if (members.isNullOrEmpty()) {
            dl.setText(R.string.download)
            return
        }
        val dm = ServiceRegistry.dataModule.downloadManager
        val states = members.map { dm.getDownloadState(it.arcid) }
        dl.setText(
            when {
                states.all { it == DownloadState.FINISH } -> R.string.download_state_downloaded
                states.any { it == DownloadState.DOWNLOAD || it == DownloadState.WAIT } ->
                    R.string.download_state_downloading
                states.any { it == DownloadState.FAILED } -> R.string.download_state_failed
                else -> R.string.download
            }
        )
    }

    /**
     * The shared tank fill path (also behind the downloads card and the
     * drawer long-press): worker / notification behavior matches ordinary
     * downloads and the group row is re-tagged with full membership.
     */
    private fun downloadTank() {
        val ctx = ehContext ?: return
        val s = viewModel.state.value ?: return
        if (s.members.isEmpty()) {
            Toast.makeText(ctx, R.string.error_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val dm = ServiceRegistry.dataModule.downloadManager
        val plan = TankFillDispatcher.plan(s.members) { dm.getDownloadState(it) }
        TankFillDispatcher.dispatch(ctx, dm, plan, s.tankId, s.name, s.profileId, s.memberIds)
        Toast.makeText(ctx, TankFillDispatcher.feedback(resources, plan), Toast.LENGTH_SHORT).show()
        updateDownloadText()
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /**
     * Whole-tank composite session; [startGlobalPage] -1 = the provider
     * restores the saved global progress. Works offline from the snapshot.
     */
    private fun openTankSession(startGlobalPage: Int) {
        val ctx = ehContext ?: return
        val s = viewModel.state.value ?: return
        if (s.members.isEmpty()) return
        val seed = TankSessionSeed(
            tankId = s.tankId,
            tankName = s.name,
            profileId = s.profileId,
            members = s.members.map { TankMemberSeed(it.arcid, it.title, it.pagecount) },
        )
        TankSeedStore.publish(seed)
        startActivity(GalleryOpenHelper.buildTankReadIntent(ctx, seed, startGlobalPage))
    }

    /**
     * Category heart (spec 2026-09-22 §4.3): the shared static-category
     * dialog acting on the TANK_ id against the tank's SOURCE server;
     * dynamic categories match server-side and are not offered. Disabled
     * offline (no server to write to).
     */
    private fun showCategoryDialog() {
        val s = viewModel.state.value ?: return
        if (s.offline) {
            ehContext?.let { Toast.makeText(it, R.string.tank_detail_offline, Toast.LENGTH_SHORT).show() }
            return
        }
        CategoryDialogHelper.showCategoryDialog(activity2, s.tankId, s.profileId) { isFavorited, name ->
            viewModel.updateFavoriteState(FavoriteState(isFavorited, name))
        }
    }

    // -------------------------------------------------------------------------
    // Tags (spec 2026-09-22 §4.4): the tank's OWN tags, single layer, editable
    // -------------------------------------------------------------------------

    private fun bindTags(s: TankDetailState) {
        val ctx = ehContext ?: return
        val layout = mTagsLayout ?: return
        val noTags = mNoTags ?: return
        GalleryTagHelper.bindTags(ctx, layoutInflater2, layout, noTags, s.tagGroups, this, this)
        // Editing writes to the source server — no server offline.
        mEditTagsBtn?.visibility = if (s.offline) View.GONE else View.VISIBLE
    }

    /** The shared dialog on the tank id with the tankoubon writer; a save reloads server truth. */
    private fun showTagEditDialog() {
        val s = viewModel.state.value ?: return
        if (s.offline) return
        TagEditDialog.show(activity2, s.tankId, s.tagGroups, s.profileId, TagEditDialog.tankoubonWriter) {
            viewModel.load()
        }
    }

    /** Chip tap = tag search on the active server, as on the archive page. */
    private fun openTagSearch(tag: String) {
        val lub = ListUrlBuilder()
        lub.mode = ListUrlBuilder.MODE_TAG
        lub.keyword = tag
        GalleryListScene.startScene(this, lub)
    }

    override fun onClick(v: View) {
        when {
            v === mTip -> reload()
            v === mRead -> openTankSession(startGlobalPage = -1)
            v === mDownload -> downloadTank()
            v === mHeartGroup -> showCategoryDialog()
            v === mEditTagsBtn -> showTagEditDialog()
            v === mMembersManage -> openMemberManagement()
            else -> (v.getTag(R.id.tag) as? String)?.let { openTagSearch(it) }
        }
    }

    override fun onLongClick(v: View): Boolean {
        val tag = v.getTag(R.id.tag) as? String ?: return false
        val ctx = ehContext ?: return false
        GalleryTagHelper.showTagDialog(this, ctx, tag)
        return true
    }

    companion object {
        /** Same key strings as TankoubonDetailScene so launchers can share a bundle. */
        const val KEY_TANK_ID = "tank_id"
        const val KEY_TANK_NAME = "tank_name"
        const val KEY_PROFILE_ID = "tank_profile_id"

        private const val REVEAL_DURATION_MS = 300L
        private const val RATING_STEP = 0.5f
        private const val MAX_STARS = 5f
        private const val SPAN_MIN = 3
        private const val SPAN_MAX = 6
        private const val INITIAL_ROWS = 4
    }
}
