package com.hippo.ehviewer.ui.scene.gallery.list

import androidx.annotation.PluralsRes
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListViewModel.BatchOp
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListViewModel.BatchResult

/**
 * Maps a finished batch to its user feedback tier: a snackbar tip for
 * download queueing and all-success outcomes, a failure-detail dialog only
 * when items actually failed. Pure resource mapping so the tiering is unit
 * testable without Android plumbing.
 */
object BatchFeedbackPresenter {

    /** Snackbar wording for a batch with no failures (incl. download queueing). */
    sealed interface Tip {
        /** Simple quantified tip rendered via getQuantityString. */
        data class Plural(@param:PluralsRes val textRes: Int, val count: Int) : Tip

        /**
         * Download batch that queued some items while skipping already-local
         * ones (R.string.batch_download_queued_some_local: %1$d queued,
         * %2$d already downloaded).
         */
        data class QueuedWithLocal(val queued: Int, val alreadyLocal: Int) : Tip
    }

    /** Failure dialog spec: quantified title plus per-item failure lines. */
    data class FailureDialog(
        @param:PluralsRes val titleRes: Int,
        val failedCount: Int,
        val succeededCount: Int,
        val failures: List<GalleryListViewModel.BatchFailure>,
    )

    fun tipFor(result: BatchResult): Tip? {
        if (result.failed.isNotEmpty()) return null
        val local = result.alreadyLocal.size
        return when {
            // Only download batches populate alreadyLocal (DownloadEntryGate).
            local == 0 -> Tip.Plural(successTextRes(result.op), result.succeeded.size)
            result.succeeded.isEmpty() -> Tip.Plural(R.plurals.batch_download_all_local, local)
            else -> Tip.QueuedWithLocal(queued = result.succeeded.size, alreadyLocal = local)
        }
    }

    fun dialogFor(result: BatchResult): FailureDialog? =
        if (result.failed.isEmpty()) {
            null
        } else {
            FailureDialog(
                titleRes = failTitleRes(result.op),
                failedCount = result.failed.size,
                succeededCount = result.succeeded.size,
                failures = result.failed,
            )
        }

    @PluralsRes
    private fun successTextRes(op: BatchOp): Int = when (op) {
        BatchOp.Download -> R.plurals.batch_download_queued
        is BatchOp.AddToCategory -> R.plurals.batch_done_category
        is BatchOp.AddToTankoubon -> R.plurals.batch_done_tankoubon
        BatchOp.ClearNew -> R.plurals.batch_done_clear_new
        BatchOp.DeleteArchives -> R.plurals.batch_done_delete
    }

    @PluralsRes
    private fun failTitleRes(op: BatchOp): Int = when (op) {
        is BatchOp.AddToCategory -> R.plurals.batch_fail_title_category
        is BatchOp.AddToTankoubon -> R.plurals.batch_fail_title_tankoubon
        BatchOp.ClearNew -> R.plurals.batch_fail_title_clear_new
        BatchOp.DeleteArchives -> R.plurals.batch_fail_title_delete
        // Download queueing is synchronous and infallible; a Download result
        // can never carry failures.
        BatchOp.Download -> error("download batches cannot fail")
    }
}
