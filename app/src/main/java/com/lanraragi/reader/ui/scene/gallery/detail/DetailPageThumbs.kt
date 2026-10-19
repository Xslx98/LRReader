package com.lanraragi.reader.ui.scene.gallery.detail

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.gallery.TankSessionRouter
import com.lanraragi.reader.settings.AppearanceSettings
import com.lanraragi.reader.ui.GalleryOpenHelper
import com.lanraragi.reader.util.collectFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The per-page thumbnail grid at the bottom of the detail page: column
 * count, state rendering, the first-viewport prefetch, jump-to-page and
 * opening the reader at a tapped page.
 */
class DetailPageThumbs(
    private val scene: GalleryDetailScene,
    private val viewModel: GalleryDetailViewModel,
    private val pageThumbsViewModel: PageThumbnailsViewModel,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private var recycler: RecyclerView? = null
    private var progress: ProgressBar? = null
    private var empty: TextView? = null
    private var adapter: PageThumbnailAdapter? = null
    private var spanCount: Int = 0

    /**
     * Wire up the per-page thumbnail grid at the bottom of the
     * detail page. Splits the spanCount calculation, adapter
     * binding and scroll-listener install into one place so the
     * onCreateView2 critical path stays readable.
     */
    fun setUp(view: View) {
        val recycler = view.findViewById<RecyclerView>(R.id.page_thumb_recycler) ?: return
        val progress = view.findViewById<ProgressBar>(R.id.page_thumb_progress)
        val empty = view.findViewById<TextView>(R.id.page_thumb_empty)
        val jumpButton = view.findViewById<TextView>(R.id.page_thumb_jump)

        this.recycler = recycler
        this.progress = progress
        this.empty = empty

        val resources = scene.resources
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
        this.spanCount = spanCount

        val adapter = PageThumbnailAdapter(
            onPageClick = { page0 -> openReaderAtPage(page0) },
            onPageRetry = { page0 -> pageThumbsViewModel.retryPage(page0) },
        )
        this.adapter = adapter

        recycler.layoutManager = GridLayoutManager(view.context, spanCount)
        // Disable item-change animations so the partial rebind on
        // notifyItemChanged(pos, PAYLOAD) does not flash the thumbnail.
        recycler.itemAnimator = null
        recycler.adapter = adapter

        recycler.addOnScrollListener(
            PrefetchScrollListener(spanCount, onWindow = pageThumbsViewModel::retainWindow) { page ->
                pageThumbsViewModel.requestPage(page)
            }
        )

        jumpButton?.setOnClickListener { showJumpToPageDialog() }
    }

    /** Starts the page-list fetch and the grid's view-scoped collectors. */
    fun observe(owner: LifecycleOwner) {
        // Kick off the page-list fetch as soon as we have an archive in
        // the nav-arg. Independent of detail metadata so it runs in
        // parallel with `requestGalleryDetail` and we don't gate the
        // thumbnail grid behind a slow metadata response.
        // Raw view-scoped launch: a per-attach one-shot (`first()`), must NOT
        // sit inside repeatOnLifecycle or it would re-run on every foreground
        // return.
        owner.lifecycleScope.launch(
            ServiceRegistry.coroutineModule.exceptionHandler
        ) {
            val arc = viewModel.archive.filterNotNull().first()
            adapter?.submitArcid(arc.arcid)
            pageThumbsViewModel.start(arc.arcid, arc.serverProfileId)
        }
        // State machine drives top-level visibility: spinner vs empty
        // text vs RecyclerView populated with pageCount empty slots.
        collectFlow(owner, pageThumbsViewModel.state) { state ->
            applyState(state)
        }
        // Per-page status snapshot drives partial cell rebinds.
        collectFlow(owner, pageThumbsViewModel.pageStates) { states ->
            adapter?.submitStates(states)
        }
    }

    /**
     * Apply a [PageThumbnailsViewModel.State] snapshot to the grid UI.
     * Toggles visibility between recycler / spinner / empty-text and,
     * on Loaded transitions, primes the initial prefetch window so the
     * user sees thumbnails populating without waiting for a scroll.
     */
    private fun applyState(state: PageThumbnailsViewModel.State) {
        val recycler = recycler ?: return
        val progress = progress
        val empty = empty
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
                adapter?.submitPageCount(state.pageCount)
                // The PrefetchScrollListener only fires on scroll, so the
                // first-viewport request kick comes from here. Posts to
                // the recycler so layout has applied before we calculate
                // visible rows.
                recycler.post { triggerInitialPrefetch(state.pageCount) }
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
        return scene.getString(resId)
    }

    /**
     * Request thumbnails for the initial viewport plus the standard
     * prefetch window. Repository requests are idempotent so callers
     * (this method and the scroll listener) coexist without dedup
     * logic here.
     */
    private fun triggerInitialPrefetch(pageCount: Int) {
        val span = spanCount.coerceAtLeast(1)
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
     * shared [GalleryOpenHelper] so the local / stream path selection
     * and warmup behaviour stay identical to the regular "Read" button.
     */
    private fun openReaderAtPage(page0: Int) {
        val archive = viewModel.getEffectiveArchive() ?: return
        val ctx = scene.getEHContext() ?: return
        // Tank-flow members read in the whole-tank session; the tapped
        // member-local page maps to its global page inside it.
        TankSessionRouter
            .tankIntentFor(ctx, archive.arcid, memberPage0 = page0)
            ?.let { tankIntent ->
                scene.startActivity(tankIntent)
                return
            }
        scene.viewLifecycleOwner.lifecycleScope.launch(
            ServiceRegistry.coroutineModule.exceptionHandler
        ) {
            val intent = withContext(ioDispatcher) {
                GalleryOpenHelper.buildReadIntent(
                    ctx,
                    archive,
                    startPage = page0,
                )
            }
            scene.startActivity(intent)
        }
    }

    /**
     * Prompt for a 1-indexed page number and snap the grid to that
     * page. Does not open the reader — that path is reserved for
     * thumbnail taps so the two interactions stay distinct.
     */
    private fun showJumpToPageDialog() {
        val ctx = scene.getEHContext() ?: return
        val loaded = pageThumbsViewModel.state.value as? PageThumbnailsViewModel.State.Loaded ?: return
        val pageCount = loaded.pageCount
        if (pageCount <= 0) return

        val container = FrameLayout(ctx).apply {
            val pad = scene.resources.getDimensionPixelSize(R.dimen.keyline_margin)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = scene.getString(R.string.lrr_page_thumbnails_jump_dialog_hint, pageCount)
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
                        scene.getString(R.string.lrr_page_thumbnails_jump_dialog_out_of_range, pageCount),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setPositiveButton
                }
                recycler?.scrollToPosition(raw - 1)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun clear() {
        // Detach the adapter explicitly so RecyclerView clears its view
        // pool — otherwise the pooled view holders keep references to
        // the previous detail page's Activity context.
        recycler?.adapter = null
        recycler = null
        progress = null
        empty = null
        adapter = null
    }

    private companion object {
        // Thumbnail grid column count is derived from screen width:
        // floor(screen_width / target_tile_width), clamped here so a
        // foldable / tablet never blows past 6 columns and a narrow
        // phone never collapses below 3. The target tile width is
        // resolved per-bind from AppearanceSettings.getDetailPageThumb
        // SizeResId() so the user's small/medium/large preference
        // takes effect on next Scene entry.
        const val SPAN_MIN: Int = 3
        const val SPAN_MAX: Int = 6

        // Initial request window: span_count × this many rows.
        // 4 rows cover the typical visible viewport + one row of
        // prefetch headroom; the scroll listener picks up the rest.
        const val INITIAL_ROWS: Int = 4
    }
}
