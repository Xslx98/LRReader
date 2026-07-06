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
    data class Tip(@param:PluralsRes val textRes: Int, val count: Int)

    /** Failure dialog spec: quantified title plus per-item failure lines. */
    data class FailureDialog(
        @param:PluralsRes val titleRes: Int,
        val failedCount: Int,
        val succeededCount: Int,
        val failures: List<GalleryListViewModel.BatchFailure>,
    )

    fun tipFor(result: BatchResult): Tip? =
        if (result.failed.isEmpty()) Tip(successTextRes(result.op), result.succeeded.size) else null

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
