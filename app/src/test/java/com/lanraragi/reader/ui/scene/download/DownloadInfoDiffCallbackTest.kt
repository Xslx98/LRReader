package com.lanraragi.reader.ui.scene.download

import com.lanraragi.reader.dao.DownloadFailureReason
import com.lanraragi.reader.dao.DownloadInfo
import com.lanraragi.reader.dao.DownloadState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadInfoDiffCallbackTest {

    private fun failed(reason: DownloadFailureReason?) = DownloadInfo().also {
        it.arcid = "a"
        it.state = DownloadState.FAILED
        it.legacy = 2
        it.failureReason = reason
    }

    private fun sameContents(old: DownloadInfo, new: DownloadInfo) =
        DownloadInfoDiffCallback(listOf(old), listOf(new)).areContentsTheSame(0, 0)

    @Test
    fun aChangedFailureReason_rebindsTheCard() {
        // The FAILED card's text includes the reason (audit 2026-11-15 B3).
        assertFalse(sameContents(failed(null), failed(DownloadFailureReason.NO_SPACE)))
        assertTrue(sameContents(failed(DownloadFailureReason.AUTH), failed(DownloadFailureReason.AUTH)))
    }
}
