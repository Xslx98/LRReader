package com.hippo.ehviewer.dao

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration path test for v29 -> v30 (MIGRATION_29_30): the
 * `DOWNLOAD_TANK_ID` grouping tag on ARCHIVE_LOCAL_STATE plus the
 * `TANK_DOWNLOAD_GROUP` table, following the established two-check
 * harness (isolated SQL + file-backed real upgrade with Room
 * validateMigration).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class RoomMigrationV29V30Test {

    private lateinit var db: SupportSQLiteDatabase

    @After
    fun tearDown() {
        if (::db.isInitialized && db.isOpen) db.close()
    }

    @Test
    fun migration_addsTankColumn_andGroupTable() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()
        )
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(29) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Only the mutated table matters for the isolated check.
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `ARCHIVE_LOCAL_STATE` (" +
                            "`ARCID` TEXT NOT NULL, `SERVER_PROFILE_ID` INTEGER NOT NULL DEFAULT 0, " +
                            "`ARCHIVE_JSON` TEXT NOT NULL, `DOWNLOAD_STATE` INTEGER, " +
                            "`DOWNLOAD_LEGACY` INTEGER NOT NULL DEFAULT 0, `DOWNLOAD_TIME` INTEGER, " +
                            "`DOWNLOAD_LABEL` TEXT, `DOWNLOAD_ARCHIVE_URI` TEXT, `DOWNLOAD_ROOT_URI` TEXT, " +
                            "`HISTORY_TIME` INTEGER, `HISTORY_MODE` INTEGER NOT NULL DEFAULT 0, " +
                            "`HISTORY_SCROLL_FRACTION` REAL, `FAVORITE_TIME` INTEGER, " +
                            "PRIMARY KEY(`ARCID`, `SERVER_PROFILE_ID`))"
                    )
                    db.execSQL(
                        "INSERT INTO ARCHIVE_LOCAL_STATE (ARCID, SERVER_PROFILE_ID, ARCHIVE_JSON, DOWNLOAD_STATE) " +
                            "VALUES ('a', 1, '{}', 2)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        db = FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase

        AppDatabase.MIGRATION_29_30.migrate(db)

        // Existing rows survive with a NULL tag.
        db.query("SELECT DOWNLOAD_TANK_ID FROM ARCHIVE_LOCAL_STATE WHERE ARCID='a'").use {
            assertTrue(it.moveToFirst())
            assertNull(it.getString(0).takeIf { _ -> !it.isNull(0) })
        }
        // Tag is writable.
        db.execSQL("UPDATE ARCHIVE_LOCAL_STATE SET DOWNLOAD_TANK_ID='TANK_1688000000' WHERE ARCID='a'")
        db.query("SELECT DOWNLOAD_TANK_ID FROM ARCHIVE_LOCAL_STATE WHERE ARCID='a'").use {
            assertTrue(it.moveToFirst())
            assertEquals("TANK_1688000000", it.getString(0))
        }
        // Group table exists with TANK_ID PK.
        db.execSQL(
            "INSERT INTO TANK_DOWNLOAD_GROUP (TANK_ID, SERVER_PROFILE_ID, NAME, MEMBER_IDS_JSON, CREATED_TIME) " +
                "VALUES ('TANK_1688000000', 1, 'Tank', '[\"a\"]', 5)"
        )
        db.query("SELECT NAME FROM TANK_DOWNLOAD_GROUP WHERE TANK_ID='TANK_1688000000'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Tank", it.getString(0))
        }
    }

    @Test
    fun room_opensCleanly_afterRealUpgrade_v29_to_v30() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "db_spike_v29.db"
        ctx.getDatabasePath(name).also { it.parentFile?.mkdirs(); it.delete() }

        // Raw v29 DB FILE with ALL SEVEN v29 tables (Room validates every table).
        val v29Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(29) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `DOWNLOAD_LABELS` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT, `LABEL` TEXT, `TIME` INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_DOWNLOAD_LABELS_TIME` ON `DOWNLOAD_LABELS` (`TIME`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `DOWNLOAD_DIRNAME` (`ARCID` TEXT NOT NULL, `DIRNAME` TEXT, PRIMARY KEY(`ARCID`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `QUICK_SEARCH` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT, `NAME` TEXT, `MODE` INTEGER NOT NULL, `CATEGORY` INTEGER NOT NULL, `KEYWORD` TEXT, `ADVANCE_SEARCH` INTEGER NOT NULL, `MIN_RATING` INTEGER NOT NULL, `PAGE_FROM` INTEGER NOT NULL, `PAGE_TO` INTEGER NOT NULL, `TIME` INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_QUICK_SEARCH_TIME` ON `QUICK_SEARCH` (`TIME`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `SERVER_PROFILES` (`ID` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `NAME` TEXT NOT NULL, `URL` TEXT NOT NULL, `IS_ACTIVE` INTEGER NOT NULL, `ALLOW_CLEARTEXT` INTEGER NOT NULL DEFAULT 1)")
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `ARCHIVE_LOCAL_STATE` (" +
                                "`ARCID` TEXT NOT NULL, `SERVER_PROFILE_ID` INTEGER NOT NULL DEFAULT 0, " +
                                "`ARCHIVE_JSON` TEXT NOT NULL, `DOWNLOAD_STATE` INTEGER, " +
                                "`DOWNLOAD_LEGACY` INTEGER NOT NULL DEFAULT 0, `DOWNLOAD_TIME` INTEGER, " +
                                "`DOWNLOAD_LABEL` TEXT, `DOWNLOAD_ARCHIVE_URI` TEXT, `DOWNLOAD_ROOT_URI` TEXT, " +
                                "`HISTORY_TIME` INTEGER, `HISTORY_MODE` INTEGER NOT NULL DEFAULT 0, " +
                                "`HISTORY_SCROLL_FRACTION` REAL, `FAVORITE_TIME` INTEGER, " +
                                "PRIMARY KEY(`ARCID`, `SERVER_PROFILE_ID`))"
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_SERVER_PROFILE_ID` ON `ARCHIVE_LOCAL_STATE` (`SERVER_PROFILE_ID`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_DOWNLOAD_TIME` ON `ARCHIVE_LOCAL_STATE` (`DOWNLOAD_TIME`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_HISTORY_TIME` ON `ARCHIVE_LOCAL_STATE` (`HISTORY_TIME`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_FAVORITE_TIME` ON `ARCHIVE_LOCAL_STATE` (`FAVORITE_TIME`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_DOWNLOAD_LABEL` ON `ARCHIVE_LOCAL_STATE` (`DOWNLOAD_LABEL`)")
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `SEARCH_HISTORY` (" +
                                "`QUERY` TEXT NOT NULL, `SERVER_PROFILE_ID` INTEGER NOT NULL, " +
                                "`LAST_USED` INTEGER NOT NULL, " +
                                "PRIMARY KEY(`QUERY`, `SERVER_PROFILE_ID`))"
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_SEARCH_HISTORY_SERVER_PROFILE_ID_LAST_USED` ON `SEARCH_HISTORY` (`SERVER_PROFILE_ID`, `LAST_USED`)")
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `DAILY_READING_AGGREGATE` (" +
                                "`EPOCH_DAY` INTEGER NOT NULL, `SERVER_PROFILE_ID` INTEGER NOT NULL, " +
                                "`PAGES_READ` INTEGER NOT NULL, `COMPLETED` INTEGER NOT NULL, " +
                                "PRIMARY KEY(`EPOCH_DAY`, `SERVER_PROFILE_ID`))"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS " +
                                "`index_DAILY_READING_AGGREGATE_SERVER_PROFILE_ID_EPOCH_DAY` " +
                                "ON `DAILY_READING_AGGREGATE` (`SERVER_PROFILE_ID`, `EPOCH_DAY`)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                }).build()
        )
        v29Helper.writableDatabase
        v29Helper.close()

        val room = Room.databaseBuilder(ctx, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_29_30)
            .build()
        try {
            room.openHelper.writableDatabase

            runBlocking {
                val dao = room.tankDownloadGroupDao()
                dao.upsert(
                    TankDownloadGroup(
                        tankId = "TANK_1688000000",
                        serverProfileId = 7L,
                        name = "Tank",
                        memberIdsJson = """["a","b"]""",
                        createdTime = 42L,
                    )
                )
                val groups = dao.observeAll().first()
                assertEquals(1, groups.size)
                assertEquals("Tank", groups.single().name)
                dao.delete("TANK_1688000000")
                assertNull(dao.getById("TANK_1688000000"))
            }
        } finally {
            room.close()
            ctx.getDatabasePath(name).delete()
        }
    }
}
