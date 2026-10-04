package com.lanraragi.reader.dao

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit 2026-10-04 C45: the "at most one download row per arcid" invariant
 * is backed by triggers that [AppDatabase.build] installs on every open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class DownloadArcidGuardTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var sql: SupportSQLiteDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(NAME)
        db = AppDatabase.build(context, NAME)
        sql = db.openHelper.writableDatabase
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(NAME)
    }

    private fun insert(arcid: String, profile: Long, downloadState: Int?) = sql.execSQL(
        "INSERT INTO ARCHIVE_LOCAL_STATE (ARCID, SERVER_PROFILE_ID, ARCHIVE_JSON, DOWNLOAD_STATE) VALUES (?, ?, '{}', ?)",
        arrayOf<Any?>(arcid, profile, downloadState)
    )

    private fun downloadRows(arcid: String): Long = sql.query(
        "SELECT COUNT(*) FROM ARCHIVE_LOCAL_STATE WHERE ARCID = ? AND DOWNLOAD_STATE IS NOT NULL",
        arrayOf(arcid)
    ).use { it.moveToFirst(); it.getLong(0) }

    @Test
    fun insertingDownloadUnderSecondProfile_aborts() {
        insert("a", 1L, 3)
        assertThrows(SQLiteConstraintException::class.java) { insert("a", 2L, 0) }
        assertEquals(1L, downloadRows("a"))
    }

    @Test
    fun turningSecondProfileHistoryRowIntoDownload_aborts() {
        insert("a", 1L, 3)
        insert("a", 2L, null) // history/favourite-only row under another profile is legal
        assertThrows(SQLiteConstraintException::class.java) {
            sql.execSQL("UPDATE ARCHIVE_LOCAL_STATE SET DOWNLOAD_STATE = 0 WHERE ARCID = 'a' AND SERVER_PROFILE_ID = 2")
        }
        assertEquals(1L, downloadRows("a"))
    }

    @Test
    fun sameProfileStateChangesAndOtherArcids_areAllowed() {
        insert("a", 1L, 0)
        sql.execSQL("UPDATE ARCHIVE_LOCAL_STATE SET DOWNLOAD_STATE = 3 WHERE ARCID = 'a' AND SERVER_PROFILE_ID = 1")
        insert("b", 2L, 0)
        assertEquals(1L, downloadRows("a"))
        assertEquals(1L, downloadRows("b"))
    }

    @Test
    fun existingViolation_skipsTriggersAndReportsArcid() {
        sql.execSQL("DROP TRIGGER `${DownloadArcidGuard.INSERT_TRIGGER}`")
        sql.execSQL("DROP TRIGGER `${DownloadArcidGuard.UPDATE_TRIGGER}`")
        insert("dup", 1L, 3)
        insert("dup", 2L, 3)

        assertEquals(listOf("dup"), DownloadArcidGuard.install(sql))
        // No trigger: a state change on either duplicate row must still succeed.
        sql.execSQL("UPDATE ARCHIVE_LOCAL_STATE SET DOWNLOAD_STATE = 0 WHERE ARCID = 'dup'")
        assertEquals(0L, triggerCount())
    }

    private fun triggerCount(): Long = sql.query(
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'lrr_uniq_download_arcid%'"
    ).use { it.moveToFirst(); it.getLong(0) }

    private companion object {
        const val NAME = "download-arcid-guard-test.db"
    }
}
