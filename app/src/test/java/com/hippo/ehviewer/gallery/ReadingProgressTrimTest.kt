package com.hippo.ehviewer.gallery

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reading_progress SharedPreferences grew unboundedly — two keys per
 * archive ever opened, with the whole XML file rewritten on every page
 * turn. [GalleryProvider2.maybeTrimReadingProgress] caps it: above
 * MAX_PROGRESS_ENTRIES archives the oldest (by `_ts`) are pruned down to
 * TRIM_TARGET, never touching the archive being read right now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ReadingProgressTrimTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        prefs = ctx.getSharedPreferences("test_reading_progress_trim", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    private fun seed(count: Int, tsBase: Long = 1_000_000L) {
        prefs.edit {
            for (i in 0 until count) {
                putInt("arc%04d".format(i), i)
                putLong("arc%04d_ts".format(i), tsBase + i)
            }
        }
    }

    @Test
    fun `below the cap nothing is trimmed`() {
        seed(GalleryProvider2.MAX_PROGRESS_ENTRIES)
        GalleryProvider2.maybeTrimReadingProgress(prefs, "arc0000")
        assertEquals(GalleryProvider2.MAX_PROGRESS_ENTRIES * 2, prefs.all.size)
    }

    @Test
    fun `above the cap oldest entries are pruned to the target`() {
        val over = GalleryProvider2.MAX_PROGRESS_ENTRIES + 20
        seed(over)
        GalleryProvider2.maybeTrimReadingProgress(prefs, "arc%04d".format(over - 1))

        val remainingArcids = prefs.all.keys.filter { !it.endsWith("_ts") }
        assertEquals(GalleryProvider2.TRIM_TARGET, remainingArcids.size)
        // Oldest were removed (page key AND its _ts key)...
        assertFalse(prefs.contains("arc0000"))
        assertFalse(prefs.contains("arc0000_ts"))
        // ...newest survive.
        assertTrue(prefs.contains("arc%04d".format(over - 1)))
        assertTrue(prefs.contains("arc%04d_ts".format(over - 1)))
    }

    @Test
    fun `the currently-read arcid is never pruned even if oldest`() {
        val over = GalleryProvider2.MAX_PROGRESS_ENTRIES + 20
        seed(over)
        // arc0000 has the oldest timestamp but is the active archive.
        GalleryProvider2.maybeTrimReadingProgress(prefs, "arc0000")
        assertTrue(prefs.contains("arc0000"))
    }

    @Test
    fun `legacy entries without timestamps count as oldest`() {
        seed(GalleryProvider2.MAX_PROGRESS_ENTRIES + 20)
        prefs.edit { putInt("legacy-no-ts", 3) } // no _ts key
        GalleryProvider2.maybeTrimReadingProgress(prefs, "arc0001")
        assertFalse(prefs.contains("legacy-no-ts"))
    }
}
