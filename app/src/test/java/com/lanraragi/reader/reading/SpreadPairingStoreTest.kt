package com.lanraragi.reader.reading

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.framework.lib.glgallery.GalleryView
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.ui.GalleryActivity
import com.lanraragi.reader.ui.reader.ReaderLaunch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A book's two-page pairing: its "shift pairing by one" and the parity the
 * reader saw last, kept per profile and per book (a whole tank as one book),
 * so a resume lands on the same pairs and a deleted profile leaves nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SpreadPairingStoreTest {

    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("spread_pairing_test", Context.MODE_PRIVATE)
    private val store = SpreadPairingStore(prefs)

    @Test
    fun `shift and parity survive a new store over the same file, per profile and per book`() {
        store.setShifted(1L, "arc", true)
        store.putParity(1L, "arc", 1, COVER_ALONE)

        val reopened = SpreadPairingStore(prefs)
        assertTrue(reopened.isShifted(1L, "arc"))
        assertEquals(1, reopened.parity(1L, "arc", COVER_ALONE))
        assertFalse(reopened.isShifted(2L, "arc"))
        assertEquals(GalleryView.PARITY_UNKNOWN, reopened.parity(2L, "arc", COVER_ALONE))
        assertFalse(reopened.isShifted(1L, "other"))
        assertEquals(GalleryView.PARITY_UNKNOWN, reopened.parity(1L, "other", COVER_ALONE))
    }

    @Test
    fun `the shift and the parity are stored apart`() {
        store.putParity(1L, "arc", 0, COVER_ALONE)
        store.setShifted(1L, "arc", true)
        store.setShifted(1L, "arc", false)
        assertEquals(0, store.parity(1L, "arc", COVER_ALONE))

        store.setShifted(1L, "arc", true)
        store.putParity(1L, "arc", 1, !COVER_ALONE)
        assertTrue(store.isShifted(1L, "arc"))
        // A single page (-1) keeps the last pairing.
        store.putParity(1L, "arc", GalleryView.PARITY_UNKNOWN, !COVER_ALONE)
        assertEquals(1, store.parity(1L, "arc", !COVER_ALONE))
    }

    /**
     * The planner's contract: a stored parity outvotes the shift, so a new
     * shift (or "first page alone" turned off) must not land on the old pairs.
     */
    @Test
    fun `flipping whether the cover stands alone flips the parity read back`() {
        store.putParity(1L, "arc", 1, COVER_ALONE)

        assertEquals(0, store.parity(1L, "arc", !COVER_ALONE))
        assertEquals(1, store.parity(1L, "arc", COVER_ALONE))
    }

    @Test
    fun `a deleted profile's pairings go, other profiles keep theirs`() {
        store.setShifted(1L, "arc", true)
        store.putParity(1L, "TANK_1", 0, COVER_ALONE)
        store.setShifted(11L, "arc", true)

        store.removeProfile(1L)

        assertFalse(store.isShifted(1L, "arc"))
        assertEquals(GalleryView.PARITY_UNKNOWN, store.parity(1L, "TANK_1", COVER_ALONE))
        assertTrue("profile 11 is not profile 1's prefix", store.isShifted(11L, "arc"))
    }

    @Test
    fun `a tank session pairs as one book, an archive under its source profile`() {
        val tank = TankSessionSeed(
            "TANK_7", "Saga", 4L,
            listOf(TankMemberSeed("m1", "One", 10), TankMemberSeed("m2", "Two", 12)),
        )
        val tankLaunch = launch(Intent().putExtra(GalleryActivity.KEY_TANK_SEED, tank))
        assertEquals(4L to "TANK_7", tankLaunch.pairingKey())

        val archive = Archive(
            arcid = "arc", title = "A", tags = emptyMap(), pagecount = 10, progress = 0,
            extension = "zip", filename = "f.zip", thumbnailUrl = "", rating = 0f, isnew = false,
            lastreadtime = 0L, summary = null, serverProfileId = 3L,
        )
        assertEquals(3L to "arc", launch(Intent().putExtra(GalleryActivity.KEY_ARCHIVE, archive)).pairingKey())

        assertNull("a bare directory stores nothing", launch(Intent()).pairingKey())
    }

    private fun launch(intent: Intent) = ReaderLaunch().also { it.readIntent(intent) }

    private companion object {
        const val COVER_ALONE = true
    }
}
