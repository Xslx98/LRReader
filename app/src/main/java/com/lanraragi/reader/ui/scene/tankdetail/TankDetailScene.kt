package com.lanraragi.reader.ui.scene.tankdetail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import com.hippo.android.resource.AttrResources
import com.hippo.ripple.Ripple
import com.lanraragi.framework.lib.yorozuya.ViewUtils
import com.lanraragi.framework.util.DrawableManager
import com.lanraragi.framework.view.ViewTransition
import com.lanraragi.framework.widget.LoadImageView
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.ArchiveCoverStamps
import com.lanraragi.reader.client.LRRCacheKeyFactory
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.domain.buildRatingEmoji
import com.lanraragi.reader.download.DownloadState
import com.lanraragi.reader.download.TankFillDispatcher
import com.lanraragi.reader.gallery.TankMemberSeed
import com.lanraragi.reader.gallery.TankSeedStore
import com.lanraragi.reader.gallery.TankSessionSeed
import com.lanraragi.reader.ui.GalleryOpenHelper
import com.lanraragi.reader.ui.scene.BaseScene
import com.lanraragi.reader.ui.scene.gallery.detail.GalleryDetailScene
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
class TankDetailScene : BaseScene(), View.OnClickListener {

    private lateinit var viewModel: TankDetailViewModel

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
        mHeart = ViewUtils.`$$`(heartGroup, R.id.heart) as TextView
        mHeartOutline = ViewUtils.`$$`(heartGroup, R.id.heart_outline) as TextView
        ensureHeartDrawables()
        setupRatingBar()

        // Tags and previews are bound by later steps; collapsed until then.
        ViewUtils.`$$`(belowHeader, R.id.tags).visibility = View.GONE
        ViewUtils.`$$`(belowHeader, R.id.previews).visibility = View.GONE

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
        mHeart = null
        mHeartOutline = null
        mCoverBinding = null
    }

    override fun onResume() {
        super.onResume()
        // The reader may have changed the cover (stamp bumped) or the
        // downloads list may have changed member states while we were away.
        viewModel.state.value?.let { bindCover(it) }
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

    override fun onClick(v: View) {
        when {
            v === mTip -> reload()
            v === mRead -> openTankSession(startGlobalPage = -1)
            v === mDownload -> downloadTank()
        }
    }

    companion object {
        /** Same key strings as TankoubonDetailScene so launchers can share a bundle. */
        const val KEY_TANK_ID = "tank_id"
        const val KEY_TANK_NAME = "tank_name"
        const val KEY_PROFILE_ID = "tank_profile_id"

        private const val REVEAL_DURATION_MS = 300L
        private const val RATING_STEP = 0.5f
        private const val MAX_STARS = 5f
    }
}
