package com.hippo.ehviewer.download

import com.hippo.ehviewer.download.DownloadEntryGate.Disposition
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the browse-entry download dispositions: only a missing row is NEW,
 * only a FINISH row is ALREADY_LOCAL (never re-queued from browse entries —
 * re-queuing a finished mirror-profile row against an unreachable source URL
 * would flip it to FAILED), everything else re-queues.
 */
class DownloadEntryGateTest {

    @Test
    fun `missing row is NEW`() {
        assertEquals(Disposition.NEW, DownloadEntryGate.disposition(DownloadState.INVALID))
    }

    @Test
    fun `finished row is ALREADY_LOCAL`() {
        assertEquals(Disposition.ALREADY_LOCAL, DownloadEntryGate.disposition(DownloadState.FINISH))
    }

    @Test
    fun `incomplete rows are RESTART`() {
        for (state in listOf(
            DownloadState.NONE,
            DownloadState.WAIT,
            DownloadState.DOWNLOAD,
            DownloadState.FAILED,
        )) {
            assertEquals("state $state", Disposition.RESTART, DownloadEntryGate.disposition(state))
        }
    }
}
