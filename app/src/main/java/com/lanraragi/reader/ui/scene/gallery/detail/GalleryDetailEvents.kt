package com.lanraragi.reader.ui.scene.gallery.detail

import android.view.View
import androidx.lifecycle.LifecycleOwner
import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.gallery.ReadingProgressTracker
import com.lanraragi.reader.util.collectFlow
import com.lanraragi.reader.util.collectFlowWhileCreated

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

        // Capture the server-side rating exactly once per detail-page
        // entry. The cache-hit / process-death-restore paths take
        // STATE_NORMAL straight from `prepareData` and never reach
        // `onGetArchiveDetailSuccessInternal`, so the capture lives here.
        // (StateFlow replay + the isNaN guard make re-subscription a no-op.)
        collectFlow(owner, viewModel.archiveDetail) { _ ->
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
        collectFlowWhileCreated(owner, viewModel.detailLoaded) { ad ->
            if (!scene.isStateNormal) {
                scene.onGetArchiveDetailSuccess(ad)
            }
            // A successful refresh after a banner-flagged failure also dismisses
            // the source-error banner — the live data is now displayed and the
            // warning no longer applies.
            scene.sourceError.hide()
        }
        // collectFlowWhileCreated: error arm of the same state machine.
        collectFlowWhileCreated(owner, viewModel.detailError) { e ->
            scene.onGetGalleryDetailFailure(e)
        }
        // Cache-first failure: page is already painted from local data
        // (LRU cache or a degraded ArchiveDetail seeded from the nav-arg
        // Archive). Show a non-blocking banner so the user can read the
        // archive offline and retry the source server when it returns.
        // collectFlowWhileCreated: banner state must not be lost to a
        // backgrounded window.
        collectFlowWhileCreated(owner, viewModel.detailErrorBanner) { e ->
            scene.sourceError.show(e)
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
