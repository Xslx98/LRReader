package com.lanraragi.reader.ui.scene

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lanraragi.reader.R
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.settings.AppearanceSettings
import com.lanraragi.reader.ui.scene.gallery.detail.PageThumbnailAdapter
import com.lanraragi.reader.ui.scene.gallery.detail.PageThumbnailsViewModel
import com.lanraragi.reader.ui.scene.gallery.detail.PrefetchScrollListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Bottom sheet that lets the user pick which page of a tankoubon member
 * becomes the tank cover (spec 2026-09-22-tank-cover §3.2/§3.3). Reuses
 * the detail page's thumbnail grid pieces: [PageThumbnailAdapter] tiles,
 * [PageThumbnailsViewModel] for the page count + 202-aware per-page
 * fetches, [PrefetchScrollListener] for scroll-driven requests. The view
 * model must be private to the caller (a fresh scene-scoped instance), not
 * the activity-scoped one the archive detail page drives.
 */
class TankCoverPagePicker(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val viewModel: PageThumbnailsViewModel,
) {

    private var dialog: BottomSheetDialog? = null
    private var collectJob: Job? = null

    /** Shows the picker for [member]; [onPick] receives the chosen 0-based page. */
    @SuppressLint("InflateParams") // dialog content: no parent to resolve layout params against
    fun show(member: Archive, onPick: (page0: Int) -> Unit) {
        dismiss()
        val sheet = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_tank_cover_picker, null, false)
        val title = view.findViewById<TextView>(R.id.cover_picker_title)
        val usePage1 = view.findViewById<View>(R.id.cover_picker_use_page1)
        val grid = view.findViewById<RecyclerView>(R.id.cover_picker_grid)
        val progress = view.findViewById<ProgressBar>(R.id.cover_picker_progress)
        val empty = view.findViewById<TextView>(R.id.cover_picker_empty)

        title.text = context.getString(R.string.tank_cover_pick_title, member.title)
        usePage1.setOnClickListener {
            sheet.dismiss()
            onPick(0)
        }

        val spanCount = spanCount()
        val adapter = PageThumbnailAdapter(
            onPageClick = { page0 ->
                sheet.dismiss()
                onPick(page0)
            },
            onPageRetry = { page0 -> viewModel.retryPage(page0) },
        )
        adapter.submitArcid(member.arcid)
        grid.layoutManager = GridLayoutManager(context, spanCount)
        grid.itemAnimator = null
        grid.adapter = adapter
        grid.addOnScrollListener(PrefetchScrollListener(spanCount) { page -> viewModel.requestPage(page) })

        collectJob = lifecycleOwner.lifecycleScope.launch {
            launch {
                viewModel.state.collect { state ->
                    when (state) {
                        PageThumbnailsViewModel.State.Idle, PageThumbnailsViewModel.State.Loading -> {
                            progress.visibility = View.VISIBLE
                            empty.visibility = View.GONE
                        }
                        is PageThumbnailsViewModel.State.Loaded -> {
                            progress.visibility = View.GONE
                            empty.visibility = View.GONE
                            adapter.submitPageCount(state.pageCount)
                            val initial = (spanCount * INITIAL_ROWS).coerceAtMost(state.pageCount)
                            for (page in 0 until initial) viewModel.requestPage(page)
                        }
                        is PageThumbnailsViewModel.State.Error -> {
                            progress.visibility = View.GONE
                            empty.visibility = View.VISIBLE
                            empty.setText(R.string.error_unknown)
                        }
                    }
                }
            }
            launch { viewModel.pageStates.collect { adapter.submitStates(it) } }
        }
        viewModel.start(member.arcid, member.serverProfileId)

        sheet.setContentView(view)
        sheet.setOnDismissListener {
            collectJob?.cancel()
            collectJob = null
            if (dialog === sheet) dialog = null
        }
        dialog = sheet
        sheet.show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun spanCount(): Int {
        val targetWidthPx = context.resources.getDimensionPixelSize(
            AppearanceSettings.getDetailPageThumbSizeResId()
        ).coerceAtLeast(1)
        return (context.resources.displayMetrics.widthPixels / targetWidthPx).coerceIn(SPAN_MIN, SPAN_MAX)
    }

    private companion object {
        const val SPAN_MIN = 3
        const val SPAN_MAX = 6
        const val INITIAL_ROWS = 6
    }
}
