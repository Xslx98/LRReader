package com.lanraragi.reader.gallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tank start page: the server wins unless a local page never reached it (audit 2026-09-22 A11). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class TankProgressTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun serverProgressWinsOverAStaleLocalPage() {
        // Read to page 57 elsewhere; this device last saved page 3.
        assertEquals(56, TankProgress.resolveStart0(localPage0 = 3, pendingPage0 = -1, serverProgress1 = 57))
    }

    @Test
    fun anUnsyncedLocalPageWins() {
        assertEquals(80, TankProgress.resolveStart0(localPage0 = 80, pendingPage0 = 80, serverProgress1 = 57))
    }

    @Test
    fun withoutServerProgressTheLocalPageIsUsed() {
        assertEquals(12, TankProgress.resolveStart0(localPage0 = 12, pendingPage0 = -1, serverProgress1 = 0))
        assertEquals(0, TankProgress.resolveStart0(localPage0 = -1, pendingPage0 = -1, serverProgress1 = 0))
    }

    @Test
    fun aSuccessfulPutClearsOnlyTheMatchingPendingPage() {
        TankProgress.markPending(ctx, "TANK_1", 10)
        TankProgress.markPending(ctx, "TANK_1", 11)
        TankProgress.markSynced(ctx, "TANK_1", 10)
        assertEquals("a newer page is still pending", 11, TankProgress.pendingPage0(ctx, "TANK_1"))
        TankProgress.markSynced(ctx, "TANK_1", 11)
        assertEquals(-1, TankProgress.pendingPage0(ctx, "TANK_1"))
    }

    @Test
    fun seedCarriesServerProgressThroughAParcel() {
        val seed = TankSessionSeed("TANK_1", "T", 1L, listOf(TankMemberSeed("a", "A", 3)), serverProgress = 57)
        val parcel = android.os.Parcel.obtain()
        seed.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        val back = TankSessionSeed.CREATOR.createFromParcel(parcel)
        parcel.recycle()
        assertEquals(57, back.serverProgress)
        assertEquals("a", back.members.single().arcid)
    }
}
