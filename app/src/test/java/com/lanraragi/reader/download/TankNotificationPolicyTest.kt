package com.lanraragi.reader.download

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Completion notification for a downloaded tankoubon fires ONCE for the
 * whole tank: only when every member reached a terminal state.
 */
class TankNotificationPolicyTest {

    private fun outcome(vararg states: DownloadState) = TankNotificationPolicy.completion(states.toList())

    @Test
    fun `all members finished is done`() {
        assertEquals(TankNotificationPolicy.Outcome.DONE, outcome(DownloadState.FINISH, DownloadState.FINISH))
    }

    @Test
    fun `any member still queued or downloading is pending`() {
        assertEquals(TankNotificationPolicy.Outcome.PENDING, outcome(DownloadState.FINISH, DownloadState.WAIT))
        assertEquals(TankNotificationPolicy.Outcome.PENDING, outcome(DownloadState.FAILED, DownloadState.DOWNLOAD))
    }

    @Test
    fun `a failed member with nothing left running fails the tank`() {
        assertEquals(TankNotificationPolicy.Outcome.FAILED, outcome(DownloadState.FINISH, DownloadState.FAILED))
    }

    @Test
    fun `a stopped or missing member keeps the tank pending`() {
        assertEquals(TankNotificationPolicy.Outcome.PENDING, outcome(DownloadState.FINISH, DownloadState.NONE))
        assertEquals(TankNotificationPolicy.Outcome.PENDING, outcome(DownloadState.FINISH, DownloadState.INVALID))
        assertEquals(TankNotificationPolicy.Outcome.PENDING, outcome())
    }
}
