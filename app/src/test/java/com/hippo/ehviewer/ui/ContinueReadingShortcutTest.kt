package com.hippo.ehviewer.ui

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.HistoryRepository
import com.lanraragi.reader.domain.Archive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Publishing contract of the continue-reading dynamic shortcut (issue #15):
 * one shortcut with the stable [ContinueReadingShortcut.SHORTCUT_ID], labeled
 * with the archive title, whose intent deep-links MainActivity with the
 * arcid + source profile id; no history snapshot -> no publish.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ContinueReadingShortcutTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var historyRepository: HistoryRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // putHistoryInfo -> trimHistory reads SharedPreferences-backed settings
        com.hippo.ehviewer.Settings.initialize(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        historyRepository = HistoryRepository(db.archiveLocalStateDao(), db)
    }

    @After
    fun tearDown() {
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        db.close()
    }

    private fun archive(arcid: String, profileId: Long, title: String) = Archive(
        arcid = arcid, title = title, tags = emptyMap(), pagecount = 10, progress = 3,
        extension = "zip", filename = "f.zip", thumbnailUrl = "", rating = 0f,
        isnew = false, lastreadtime = 100L, summary = null, serverProfileId = profileId,
    )

    @Test
    fun publish_createsShortcut_withTitleAndDeepLinkExtras() = runBlocking {
        val arcid = "a".repeat(40)
        historyRepository.putHistoryInfo(archive(arcid, 7L, "My Book"))

        ContinueReadingShortcut.publish(context, historyRepository, arcid, 7L)

        val shortcuts = ShortcutManagerCompat.getDynamicShortcuts(context)
        assertEquals(1, shortcuts.size)
        val s = shortcuts.single()
        assertEquals(ContinueReadingShortcut.SHORTCUT_ID, s.id)
        assertEquals("My Book", s.shortLabel.toString())
        val intent = s.intent!!
        assertEquals(ContinueReadingShortcut.ACTION_CONTINUE_READING, intent.action)
        assertEquals(arcid, intent.getStringExtra(ContinueReadingShortcut.KEY_ARCID))
        assertEquals(7L, intent.getLongExtra(ContinueReadingShortcut.KEY_PROFILE_ID, -1L))
    }

    @Test
    fun publish_withoutHistorySnapshot_publishesNothing() = runBlocking {
        ContinueReadingShortcut.publish(context, historyRepository, "b".repeat(40), 7L)

        assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).isEmpty())
    }

    @Test
    fun publish_again_replacesInsteadOfAccumulating() = runBlocking {
        val first = "c".repeat(40)
        val second = "d".repeat(40)
        historyRepository.putHistoryInfo(archive(first, 7L, "First"))
        historyRepository.putHistoryInfo(archive(second, 7L, "Second"))

        ContinueReadingShortcut.publish(context, historyRepository, first, 7L)
        ContinueReadingShortcut.publish(context, historyRepository, second, 7L)

        val shortcuts = ShortcutManagerCompat.getDynamicShortcuts(context)
        assertEquals(1, shortcuts.size)
        assertEquals("Second", shortcuts.single().shortLabel.toString())
    }
}
