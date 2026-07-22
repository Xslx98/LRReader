package com.hippo.ehviewer.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.gallery.ReadingSessionEnd
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Daily reading aggregate accumulation (issue #20): exactly one row per
 * (day × profile); same-day sessions accumulate into the same row; pages
 * delta never negative; completed counts only completion CROSSINGS.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class DailyReadingAggregateRecorderTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun end(
        profileId: Long = 7L,
        startPage: Int,
        endPage: Int,
        pageCount: Int = 20,
    ) = ReadingSessionEnd("a".repeat(40), profileId, startPage, endPage, pageCount)

    @Test
    fun oneSession_writesExactlyOneRow() = runBlocking {
        DailyReadingAggregateRecorder.record(db, end(startPage = 0, endPage = 5), epochDay = 100L)

        val rows = db.statsDao().getAllDailyAggregates()
        assertEquals(1, rows.size)
        assertEquals(100L, rows.single().epochDay)
        assertEquals(7L, rows.single().serverProfileId)
        assertEquals(5L, rows.single().pagesRead)
        assertEquals(0, rows.single().completed)
    }

    @Test
    fun secondSessionSameDay_accumulatesIntoTheSameRow() = runBlocking {
        DailyReadingAggregateRecorder.record(db, end(startPage = 0, endPage = 5), epochDay = 100L)
        DailyReadingAggregateRecorder.record(db, end(startPage = 5, endPage = 12), epochDay = 100L)

        val rows = db.statsDao().getAllDailyAggregates()
        assertEquals(1, rows.size)
        assertEquals(12L, rows.single().pagesRead)
    }

    @Test
    fun backwardJump_neverGoesNegative() = runBlocking {
        DailyReadingAggregateRecorder.record(db, end(startPage = 10, endPage = 3), epochDay = 100L)

        assertEquals(0L, db.statsDao().getAllDailyAggregates().single().pagesRead)
    }

    @Test
    fun completionCrossing_incrementsOnce_reopenAtLastPageDoesNot() = runBlocking {
        // Crossing: start before the last page, end on it.
        DailyReadingAggregateRecorder.record(db, end(startPage = 15, endPage = 19), epochDay = 100L)
        // Re-open an already-finished archive at its last page: no crossing.
        DailyReadingAggregateRecorder.record(db, end(startPage = 19, endPage = 19), epochDay = 100L)

        assertEquals(1, db.statsDao().getAllDailyAggregates().single().completed)
    }

    @Test
    fun differentDayOrProfile_getSeparateRows() = runBlocking {
        DailyReadingAggregateRecorder.record(db, end(startPage = 0, endPage = 1), epochDay = 100L)
        DailyReadingAggregateRecorder.record(db, end(startPage = 0, endPage = 1), epochDay = 101L)
        DailyReadingAggregateRecorder.record(
            db, end(profileId = 8L, startPage = 0, endPage = 1), epochDay = 100L
        )

        assertEquals(3, db.statsDao().getAllDailyAggregates().size)
        assertEquals(2, db.statsDao().getDailyAggregatesForProfile(7L).size)
    }

    @Test
    fun nonPositiveProfile_isIgnored() = runBlocking {
        DailyReadingAggregateRecorder.record(db, end(profileId = 0L, startPage = 0, endPage = 5), epochDay = 100L)
        assertEquals(0, db.statsDao().getAllDailyAggregates().size)
    }
}
