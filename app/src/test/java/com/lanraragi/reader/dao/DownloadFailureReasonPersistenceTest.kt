package com.lanraragi.reader.dao

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A FAILED download keeps its [DownloadFailureReason] across the Room
 * round trip (schema v32, audit 2026-11-15 B3): the downloads card binds
 * Room-decoded items, and a reason held only in memory never reached it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class DownloadFailureReasonPersistenceTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: DownloadDbRepository

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        repo = DownloadDbRepository(
            db.archiveLocalStateDao(), db.downloadDao(), db, Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun failed(reason: DownloadFailureReason?) = DownloadInfo().also {
        it.arcid = "arc-f"
        it.title = "F"
        it.state = DownloadState.FAILED
        it.legacy = 3
        it.time = 1L
        it.serverProfileId = 1L
        it.failureReason = reason
    }

    @Test
    fun failedReason_reachesTheObservedList_andTheBootLoad() = runTest {
        // Persistence goes through snapshot(), as DownloadRepository.persistInfo does.
        repo.putDownloadInfo(failed(DownloadFailureReason.NO_SPACE).snapshot())

        assertEquals(DownloadFailureReason.NO_SPACE, repo.observeDownloads().first().single().failureReason)
        assertEquals(DownloadFailureReason.NO_SPACE, repo.getAllDownloadInfo().single().failureReason)
    }

    @Test
    fun aClearedReason_isPersistedAsNull() = runTest {
        repo.putDownloadInfo(failed(DownloadFailureReason.AUTH))
        repo.putDownloadInfo(failed(null).also { it.state = DownloadState.DOWNLOAD })

        assertNull(repo.getAllDownloadInfo().single().failureReason)
    }

    @Test
    fun clearingTheDownloadSubsystem_dropsTheReason() = runTest {
        // A FAILED download sharing its row with history: removing the
        // download must not leave a reason for a later download to show.
        repo.putDownloadInfo(failed(DownloadFailureReason.NOT_FOUND))
        db.archiveLocalStateDao().fillHistoryIfAbsent("arc-f", 1L, 5L, 0)

        db.archiveLocalStateDao().clearDownloadSubsystem("arc-f")

        assertNull(db.archiveLocalStateDao().loadByArcidAndProfile("arc-f", 1L)!!.downloadFailureReason)
    }

    @Test
    fun converter_readsAnUnknownName_asNull() {
        assertEquals("SERVER", DownloadFailureReasonConverter.toName(DownloadFailureReason.SERVER))
        assertEquals(DownloadFailureReason.SERVER, DownloadFailureReasonConverter.fromName("SERVER"))
        assertNull(DownloadFailureReasonConverter.fromName("FROM_A_NEWER_BUILD"))
        assertNull(DownloadFailureReasonConverter.fromName(null))
    }

    @Test
    fun migration31to32_addsANullableColumn_keepingRows() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()
        )
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(31) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE `ARCHIVE_LOCAL_STATE` (`ARCID` TEXT NOT NULL, " +
                            "`SERVER_PROFILE_ID` INTEGER NOT NULL DEFAULT 0, `ARCHIVE_JSON` TEXT NOT NULL, " +
                            "`DOWNLOAD_STATE` INTEGER, PRIMARY KEY(`ARCID`, `SERVER_PROFILE_ID`))"
                    )
                    db.execSQL("INSERT INTO ARCHIVE_LOCAL_STATE VALUES ('a', 1, '{}', 4)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        val sql = FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
        try {
            AppDatabase.MIGRATION_31_32.migrate(sql)

            sql.query("SELECT DOWNLOAD_STATE, DOWNLOAD_FAILURE_REASON FROM ARCHIVE_LOCAL_STATE").use {
                assertTrue(it.moveToFirst())
                assertEquals(4, it.getInt(0))
                assertTrue(it.isNull(1))
            }
        } finally {
            sql.close()
        }
    }
}
