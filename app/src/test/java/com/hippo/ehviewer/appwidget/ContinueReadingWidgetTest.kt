package com.hippo.ehviewer.appwidget

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.R
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.HistoryInfo
import com.hippo.ehviewer.dao.HistoryRepository
import com.hippo.ehviewer.ui.ContinueReadingShortcut
import com.lanraragi.reader.domain.Archive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rendering + content-selection contract of the continue-reading widget
 * (issue #9): archive rows render title/progress with the shortcut's exact
 * deep-link contract; no valid history renders the empty state; the
 * launcher/self-heal path picks the most recent decodable history row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ContinueReadingWidgetTest {

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
        db.close()
    }

    private fun archive(
        arcid: String,
        profileId: Long,
        title: String,
        pagecount: Int = 10,
        progress: Int = 3,
    ) = Archive(
        arcid = arcid, title = title, tags = emptyMap(), pagecount = pagecount,
        progress = progress, extension = "zip", filename = "f.zip", thumbnailUrl = "",
        rating = 0f, isnew = false, lastreadtime = 100L, summary = null,
        serverProfileId = profileId,
    )

    private fun historyInfo(arcid: String, profileId: Long, title: String, time: Long) =
        HistoryInfo().apply {
            this.arcid = arcid
            this.title = title
            this.serverProfileId = profileId
            this.time = time
        }

    /** Inflate the RemoteViews into real views for content assertions. */
    private fun applied(views: android.widget.RemoteViews): View =
        views.apply(context, FrameLayout(context))

    private fun View.text(id: Int): String =
        findViewById<TextView>(id).text.toString()

    private fun View.visibility(id: Int): Int =
        findViewById<View>(id).visibility

    // ---- buildViews ----

    @Test
    fun buildViews_rendersTitleAndProgress() {
        val root = applied(
            ContinueReadingWidget.buildViews(
                context, archive("a".repeat(40), 7L, "My Book", pagecount = 100, progress = 12)
            )
        )

        assertEquals("My Book", root.text(R.id.appwidget_title))
        assertEquals("12 / 100", root.text(R.id.appwidget_progress))
        assertEquals(View.VISIBLE, root.visibility(R.id.appwidget_title))
        assertEquals(View.VISIBLE, root.visibility(R.id.appwidget_progress))
        assertEquals(View.GONE, root.visibility(R.id.appwidget_empty))
    }

    @Test
    fun buildViews_blankTitle_fallsBackToLabel() {
        val root = applied(
            ContinueReadingWidget.buildViews(context, archive("b".repeat(40), 7L, ""))
        )

        assertEquals(
            context.getString(R.string.shortcut_continue_reading),
            root.text(R.id.appwidget_title)
        )
    }

    @Test
    fun buildViews_zeroPagecount_hidesProgress() {
        val root = applied(
            ContinueReadingWidget.buildViews(
                context, archive("c".repeat(40), 7L, "Book", pagecount = 0, progress = 0)
            )
        )

        assertEquals(View.GONE, root.visibility(R.id.appwidget_progress))
    }

    @Test
    fun buildViews_nullArchive_rendersEmptyState() {
        val root = applied(ContinueReadingWidget.buildViews(context, null))

        assertEquals(View.GONE, root.visibility(R.id.appwidget_title))
        assertEquals(View.GONE, root.visibility(R.id.appwidget_progress))
        assertEquals(View.VISIBLE, root.visibility(R.id.appwidget_empty))
        assertEquals(
            context.getString(R.string.appwidget_continue_reading_empty),
            root.text(R.id.appwidget_empty)
        )
    }

    // ---- deep-link contract ----

    @Test
    fun continueIntent_matchesShortcutDeepLinkContract() {
        val arcid = "d".repeat(40)
        val intent = ContinueReadingWidget.continueIntent(context, arcid, 7L)

        assertEquals(ContinueReadingShortcut.ACTION_CONTINUE_READING, intent.action)
        assertEquals(arcid, intent.getStringExtra(ContinueReadingShortcut.KEY_ARCID))
        assertEquals(7L, intent.getLongExtra(ContinueReadingShortcut.KEY_PROFILE_ID, -1L))
    }

    // ---- progress text ----

    @Test
    fun progressText_formatsAndClamps() {
        assertEquals("12 / 100", ContinueReadingWidget.progressText(12, 100))
        assertEquals("100 / 100", ContinueReadingWidget.progressText(120, 100))
        assertNull(ContinueReadingWidget.progressText(0, 100))
        assertNull(ContinueReadingWidget.progressText(5, 0))
    }

    // ---- content selection (launcher/self-heal path) ----

    @Test
    fun latestArchive_picksMostRecentHistoryRow() = runBlocking {
        historyRepository.putHistoryInfoList(
            listOf(
                historyInfo("e".repeat(40), 7L, "Older", time = 1_000L),
                historyInfo("f".repeat(40), 7L, "Newer", time = 2_000L),
            )
        )

        assertEquals("Newer", ContinueReadingWidget.latestArchive(historyRepository)?.title)
    }

    @Test
    fun latestArchive_spansProfiles() = runBlocking {
        historyRepository.putHistoryInfoList(
            listOf(
                historyInfo("g".repeat(40), 7L, "Profile7", time = 1_000L),
                historyInfo("h".repeat(40), 9L, "Profile9", time = 5_000L),
            )
        )

        assertEquals("Profile9", ContinueReadingWidget.latestArchive(historyRepository)?.title)
    }

    @Test
    fun latestArchive_emptyHistory_returnsNull() = runBlocking {
        assertNull(ContinueReadingWidget.latestArchive(historyRepository))
    }

    // ---- no-widget guard ----

    @Test
    fun update_withNoBoundWidget_isANoop() = runBlocking {
        // No widget instance on the (shadow) launcher: must return without
        // touching AppWidgetManager or throwing.
        historyRepository.putHistoryInfo(archive("i".repeat(40), 7L, "Book"))
        ContinueReadingWidget.update(context, historyRepository, "i".repeat(40), 7L)
        ContinueReadingWidget.refresh(context, historyRepository)
    }
}
