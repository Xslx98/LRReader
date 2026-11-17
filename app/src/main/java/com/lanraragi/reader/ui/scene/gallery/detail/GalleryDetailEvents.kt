package com.lanraragi.reader.ui.scene.gallery.detail

import android.view.View
import androidx.lifecycle.LifecycleOwner
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.reading.ReadingProgressTracker
import com.lanraragi.reader.ui.collectFlow
import com.lanraragi.reader.ui.collectFlowWhileCreated

/**
 * The view-scoped collectors of the detail page: load success / failure,
 * the source-error banner, rating rollbacks, download state, local reading
 * progress, the tankoubon section and cover changes.
 */
class GalleryDetailEvents(
    private val scene: GalleryDetailScene,
    private val viewModel: GalleryDetailViewModel,
) {

    /** UI-4: every collector below is view-scoped; called from onViewCreated. */
    fun install(owner: LifecycleOwner, view: View) {
        // The scene framework detaches covered scenes (view destroyed,
        // fragment alive); the previous fragment-scoped launches stacked one
        // collector set per re-attach.

        // The page state: spinner, error, the detail (with the source-error
        // banner when it shows cached data after a failed fetch).
        // collectFlowWhileCreated: a refresh that finishes while the app is
        // backgrounded is rendered now, not on return.
        collectFlowWhileCreated(owner, viewModel.state) { state ->
            scene.render(state)
        }
        // After a rollback the optimistic write to currentRating /
        // archiveDetail has been reverted on the IO thread; reflect the
        // restored value on the rating bar and toast the error so the
        // user knows their change did not stick.
        // collectFlowWhileCreated: losing the event would leave the rating
        // bar showing the rolled-back optimistic value.
        collectFlowWhileCreated(owner, viewModel.ratingError) { e ->
            scene.ratingController.onRatingError(view, scene.getEHContext(), e)
        }
        // Observe download state changes from ViewModel (replaces DownloadHelper listener)
        collectFlow(owner, viewModel.downloadState) {
            scene.actionHandler?.updateDownloadText()
        }
        // The download button's source label and the header's source badge
        // read the profile snapshot, which is still empty at cold start:
        // re-bind both when it lands and after a profile rename or delete.
        collectFlow(owner, ServiceRegistry.dataModule.profileLookupCache.snapshot) {
            scene.actionHandler?.updateDownloadText()
            viewModel.archive.value?.let { scene.headerBinder?.bindSourceBadge(it) }
        }
        // Observe local reading progress so the header refreshes whenever the
        // reader writes a new page (StateFlow replays its current value to new
        // collectors, so this also covers returning from the reader to a paused
        // detail Scene). Replaces the previous one-shot onResume re-read.
        collectFlow(owner, viewModel.localReadingPage) { localPage0 ->
            applyLocalReadingProgress(localPage0)
        }
        // "Belongs to Tankoubons" section — an empty list keeps it hidden.
        collectFlow(owner, viewModel.archiveTankoubons) { tanks ->
            scene.tankBinder?.bind(tanks)
        }
        // The reader (a separate activity on top) sets the cover while this
        // scene is STOPPED. A replay-0 SharedFlow drops emissions that have
        // no live subscriber, so collect for the whole view lifetime (the
        // view survives under another activity); the reload is a cache-key
        // change plus a fetch, harmless while stopped.
        collectFlowWhileCreated(owner, AppEventBus.archiveCoverChangedEvent) { event ->
            val archive = viewModel.archive.value ?: return@collectFlowWhileCreated
            if (archive.arcid == event.arcid) scene.headerBinder?.reloadCover(archive)
        }
    }

    private fun applyLocalReadingProgress(localPage0: Int) {
        // Sentinel: SP has never been written, defer to server-reported progress.
        if (localPage0 == ReadingProgressTracker.NO_LOCAL_PROGRESS) return
        val ad = viewModel.archiveDetail.value
        val archive = viewModel.archive.value
        val totalPages = ad?.archive?.pagecount ?: archive?.pagecount ?: return
        val localPage1 = localPage0 + 1
        // Local progress is authoritative once the reader has written it, so
        // sync the binder unconditionally — including going backwards. The
        // ArchiveDetail itself stays immutable; bindReadProgress is the
        // single point that owns the displayed value.
        scene.headerBinder?.bindReadProgress(localPage1, totalPages)
    }
}
