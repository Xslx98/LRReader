package com.hippo.ehviewer.ui.scene.gallery.list

import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListViewModel.BatchFailure
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListViewModel.BatchOp
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListViewModel.BatchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchFeedbackPresenterTest {

    private fun result(op: BatchOp, succeeded: Int, failed: Int, alreadyLocal: Int = 0) = BatchResult(
        op = op,
        succeeded = List(succeeded) { "ok-$it" },
        failed = List(failed) { BatchFailure("bad-$it", "title-$it", "reason-$it") },
        alreadyLocal = List(alreadyLocal) { "local-$it" },
    )

    private fun pluralTipFor(r: BatchResult) =
        BatchFeedbackPresenter.tipFor(r) as BatchFeedbackPresenter.Tip.Plural

    @Test
    fun downloadQueueing_mapsToQueuedTip_andNeverADialog() {
        val r = result(BatchOp.Download, succeeded = 3, failed = 0)
        assertEquals(
            BatchFeedbackPresenter.Tip.Plural(R.plurals.batch_download_queued, 3),
            BatchFeedbackPresenter.tipFor(r),
        )
        assertNull(BatchFeedbackPresenter.dialogFor(r))
    }

    @Test
    fun downloadAllAlreadyLocal_mapsToAllLocalTip() {
        val r = result(BatchOp.Download, succeeded = 0, failed = 0, alreadyLocal = 2)
        assertEquals(
            BatchFeedbackPresenter.Tip.Plural(R.plurals.batch_download_all_local, 2),
            BatchFeedbackPresenter.tipFor(r),
        )
        assertNull(BatchFeedbackPresenter.dialogFor(r))
    }

    @Test
    fun downloadMixedQueuedAndLocal_mapsToTwoCountTip() {
        val r = result(BatchOp.Download, succeeded = 3, failed = 0, alreadyLocal = 2)
        assertEquals(
            BatchFeedbackPresenter.Tip.QueuedWithLocal(queued = 3, alreadyLocal = 2),
            BatchFeedbackPresenter.tipFor(r),
        )
        assertNull(BatchFeedbackPresenter.dialogFor(r))
    }

    @Test
    fun allSuccess_mapsToOpSpecificTips() {
        assertEquals(
            R.plurals.batch_done_category,
            pluralTipFor(result(BatchOp.AddToCategory("c1"), 2, 0)).textRes,
        )
        assertEquals(
            R.plurals.batch_done_tankoubon,
            pluralTipFor(result(BatchOp.AddToTankoubon("t1"), 2, 0)).textRes,
        )
        assertEquals(
            R.plurals.batch_done_clear_new,
            pluralTipFor(result(BatchOp.ClearNew, 2, 0)).textRes,
        )
        assertEquals(
            R.plurals.batch_done_delete,
            pluralTipFor(result(BatchOp.DeleteArchives, 2, 0)).textRes,
        )
    }

    @Test
    fun failures_suppressTip_andMapToOpSpecificDialog() {
        val r = result(BatchOp.AddToCategory("c1"), succeeded = 10, failed = 2)
        assertNull(BatchFeedbackPresenter.tipFor(r))
        val dialog = BatchFeedbackPresenter.dialogFor(r)!!
        assertEquals(R.plurals.batch_fail_title_category, dialog.titleRes)
        assertEquals(2, dialog.failedCount)
        assertEquals(10, dialog.succeededCount)
        assertEquals(r.failed, dialog.failures)
    }

    @Test
    fun failTitles_coverEveryNetworkOp() {
        assertEquals(
            R.plurals.batch_fail_title_tankoubon,
            BatchFeedbackPresenter.dialogFor(result(BatchOp.AddToTankoubon("t"), 0, 1))!!.titleRes,
        )
        assertEquals(
            R.plurals.batch_fail_title_clear_new,
            BatchFeedbackPresenter.dialogFor(result(BatchOp.ClearNew, 0, 1))!!.titleRes,
        )
        assertEquals(
            R.plurals.batch_fail_title_delete,
            BatchFeedbackPresenter.dialogFor(result(BatchOp.DeleteArchives, 0, 1))!!.titleRes,
        )
    }
}
