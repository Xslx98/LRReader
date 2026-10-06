package com.lanraragi.reader.gallery

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Audit C37: local progress is kept per server profile and written only when it changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LocalReadingProgressTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var profile = 1L

    @Before
    fun setUp() {
        LocalReadingProgress.resetForTest()
        LocalReadingProgress.profileId = { profile }
        LocalReadingProgress.prefs(ctx).edit().clear().commit()
    }

    @After
    fun tearDown() {
        LocalReadingProgress.resetForTest()
    }

    @Test
    fun the_same_archive_keeps_one_position_per_server() {
        LocalReadingProgress.save(ctx, "arc", 5, nowSeconds = 100)
        profile = 2
        assertEquals("server 2 has not read it", 0, LocalReadingProgress.load(ctx, "arc"))
        LocalReadingProgress.save(ctx, "arc", 9, nowSeconds = 200)

        profile = 1
        assertEquals(5, LocalReadingProgress.load(ctx, "arc"))
        assertEquals(100L, LocalReadingProgress.loadTimestamp(ctx, "arc"))
        profile = 2
        assertEquals(9, LocalReadingProgress.load(ctx, "arc"))
    }

    @Test
    fun saving_the_stored_page_again_writes_nothing() {
        assertTrue(LocalReadingProgress.save(ctx, "arc", 5, nowSeconds = 100))
        assertFalse(LocalReadingProgress.save(ctx, "arc", 5, nowSeconds = 200))
        assertEquals(100L, LocalReadingProgress.loadTimestamp(ctx, "arc"))
        assertTrue(LocalReadingProgress.save(ctx, "arc", 6, nowSeconds = 300))
        assertEquals(300L, LocalReadingProgress.loadTimestamp(ctx, "arc"))
    }

    @Test
    fun legacy_entries_move_to_the_profile_and_a_stored_one_wins() {
        val prefs = LocalReadingProgress.prefs(ctx)
        prefs.edit {
            putInt("old", 3); putLong("old_ts", 50)
            putInt("both", 4); putLong("both_ts", 60)
            putInt("7:both", 8); putLong("7:both_ts", 70)
            putLong("stray_ts", 1)
        }

        assertEquals(1, LocalReadingProgress.migrateLegacy(prefs, profileId = 7))

        assertEquals(3, prefs.getInt("7:old", 0))
        assertEquals(50L, prefs.getLong("7:old_ts", 0))
        assertEquals(8, prefs.getInt("7:both", 0))
        assertEquals(setOf("7:old", "7:old_ts", "7:both", "7:both_ts"), prefs.all.keys)
        assertEquals("idempotent", 0, LocalReadingProgress.migrateLegacy(prefs, profileId = 7))
    }

    @Test
    fun the_store_is_trimmed_once_it_grows_past_the_cap() {
        val over = LocalReadingProgress.MAX_ENTRIES + 1
        for (i in 0 until over) LocalReadingProgress.save(ctx, "arc$i", 1, nowSeconds = i.toLong())

        val kept = LocalReadingProgress.prefs(ctx).all.keys.count { !it.endsWith(LocalReadingProgress.TS_SUFFIX) }
        assertEquals(LocalReadingProgress.TRIM_TARGET, kept)
        assertEquals("the newest survives", 1, LocalReadingProgress.load(ctx, "arc${over - 1}"))
        assertEquals("the oldest is gone", 0, LocalReadingProgress.load(ctx, "arc0"))
    }
}
