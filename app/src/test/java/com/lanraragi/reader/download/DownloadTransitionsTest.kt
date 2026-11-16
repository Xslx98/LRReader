package com.lanraragi.reader.download

import com.lanraragi.reader.dao.DownloadFailureReason
import com.lanraragi.reader.dao.DownloadInfo
import com.lanraragi.reader.dao.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** [DownloadInfo.transition], the only writer of a live download's state, and [WorkerResult]. */
class DownloadTransitionsTest {

    private fun info(state: DownloadState) = DownloadInfo().apply {
        arcid = "a"
        this.state = state
    }

    @Test
    fun theLegalMoves_areExactlyTheLifecycle() {
        val legal = DownloadState.entries.flatMap { from ->
            DownloadState.entries.filter { to -> DownloadTransitions.isLegal(from, to) }.map { from to it }
        }.toSet()

        assertEquals(
            setOf(
                DownloadState.NONE to DownloadState.WAIT,
                DownloadState.FAILED to DownloadState.WAIT,
                DownloadState.FINISH to DownloadState.WAIT,
                DownloadState.INVALID to DownloadState.WAIT,
                DownloadState.WAIT to DownloadState.DOWNLOAD,
                DownloadState.WAIT to DownloadState.NONE,
                DownloadState.DOWNLOAD to DownloadState.NONE,
                DownloadState.DOWNLOAD to DownloadState.FINISH,
                DownloadState.DOWNLOAD to DownloadState.FAILED,
            ),
            legal,
        )
    }

    @Test
    fun anIllegalMove_failsInADebugBuild() {
        val finished = info(DownloadState.FINISH)
        assertThrows(IllegalStateException::class.java) { finished.transition(DownloadState.DOWNLOAD) }
        assertThrows(IllegalStateException::class.java) { info(DownloadState.NONE).transition(DownloadState.FAILED) }
    }

    @Test
    fun aFailure_carriesItsReasonAndTheMissingPages() {
        val d = info(DownloadState.DOWNLOAD)
        d.transition(DownloadState.FAILED, DownloadFailureReason.NO_SPACE, remaining = 7)

        assertEquals(DownloadState.FAILED, d.state)
        assertEquals(DownloadFailureReason.NO_SPACE, d.failureReason)
        assertEquals(7, d.legacy)
    }

    @Test
    fun leavingAFailure_clearsItsReason() {
        val d = info(DownloadState.DOWNLOAD)
        d.transition(DownloadState.FAILED, DownloadFailureReason.SERVER, remaining = 2)

        d.transition(DownloadState.WAIT)
        assertNull("a queued download shows no old failure", d.failureReason)
        assertEquals("the page count stays for the card", 2, d.legacy)

        d.transition(DownloadState.DOWNLOAD)
        assertEquals("unknown while downloading", -1, d.legacy)
        d.transition(DownloadState.FINISH)
        assertEquals(0, d.legacy)
        assertNull(d.failureReason)
    }

    @Test
    fun aReasonIsNeverRecordedOutsideAFailure() {
        val d = info(DownloadState.DOWNLOAD)
        d.transition(DownloadState.FINISH, DownloadFailureReason.SERVER)
        assertNull(d.failureReason)
    }

    @Test
    fun workerResult_aZeroPageRunIsAFailure_aFullRunAFinish() {
        assertEquals(WorkerResult.Failed(DownloadFailureReason.AUTH), WorkerResult.of(0, 0, 0, DownloadFailureReason.AUTH))
        assertEquals(WorkerResult.Finished(downloaded = 4, total = 10), WorkerResult.of(10, 4, 10, DownloadFailureReason.SERVER))

        val partial = WorkerResult.of(6, 6, 10, DownloadFailureReason.SERVER)
        assertTrue(partial is WorkerResult.Failed)
        assertEquals(4, (partial as WorkerResult.Failed).remaining)
        assertFalse(WorkerResult.of(0, 0, 10, null) is WorkerResult.Finished)
    }
}
