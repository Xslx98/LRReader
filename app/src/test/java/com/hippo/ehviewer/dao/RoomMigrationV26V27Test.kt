package com.hippo.ehviewer.dao

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration path test for v26 -> v27 (MIGRATION_26_27): ARCHIVE_LOCAL_STATE
 * primary key changes from (ARCID) to composite (ARCID, SERVER_PROFILE_ID).
 *
 * Two checks:
 * 1. [migration_makesCompositePk_preservesData_allowsTwoRowsSameArcid] runs the
 *    migration SQL in isolation (the established harness) and asserts the PK,
 *    data preservation, indices, and that two profiles can now coexist for one
 *    arcid.
 * 2. [room_opensCleanly_afterRealUpgrade_v26_to_v27] is the gold-standard check:
 *    it builds a real [AppDatabase] through the migration on a FILE-backed DB so
 *    Room's own `validateMigration` runs — guarding against a migrated schema
 *    that Room rejects only at runtime (the v1.15.1 release-only-crash lesson).
 *    Adding ADR-003 §3's hand-built partial unique index to the migration makes
 *    THIS test throw, which is the empirical proof for the repository-only
 *    invariant decision.
 *
 * Run with: ./gradlew testAppReleaseDebugUnitTest --tests "*.RoomMigrationV26V27Test"
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class RoomMigrationV26V27Test {

    private lateinit var db: SupportSQLiteDatabase

    @After
    fun tearDown() {
        if (::db.isInitialized && db.isOpen) db.close()
    }

    private fun createV26Db(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()
        )
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(26) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    createV26ArchiveLocalState(db)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // unused -- migrate() is invoked manually
                }
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    private fun primaryKeyColumns(table: String): List<String> {
        val cols = sortedMapOf<Int, String>()
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val nameIdx = c.getColumnIndexOrThrow("name")
            val pkIdx = c.getColumnIndexOrThrow("pk")
            while (c.moveToNext()) {
                val pkPos = c.getInt(pkIdx)
                if (pkPos > 0) cols[pkPos] = c.getString(nameIdx)
            }
        }
        return cols.values.toList()
    }

    @Test
    fun migration_makesCompositePk_preservesData_allowsTwoRowsSameArcid() {
        db = createV26Db()
        db.execSQL(
            "INSERT INTO ARCHIVE_LOCAL_STATE (ARCID, SERVER_PROFILE_ID, ARCHIVE_JSON, FAVORITE_TIME) " +
                "VALUES ('leg', 0, '{\"arcid\":\"leg\"}', 111)"
        )
        db.execSQL(
            "INSERT INTO ARCHIVE_LOCAL_STATE (ARCID, SERVER_PROFILE_ID, ARCHIVE_JSON, DOWNLOAD_STATE, DOWNLOAD_TIME) " +
                "VALUES ('shared', 10, '{\"arcid\":\"shared\"}', 2, 222)"
        )

        AppDatabase.MIGRATION_26_27.migrate(db)

        // composite PK is now (ARCID, SERVER_PROFILE_ID)
        assertEquals(listOf("ARCID", "SERVER_PROFILE_ID"), primaryKeyColumns("ARCHIVE_LOCAL_STATE"))

        // pre-existing data preserved
        db.query("SELECT FAVORITE_TIME FROM ARCHIVE_LOCAL_STATE WHERE ARCID='leg' AND SERVER_PROFILE_ID=0").use {
            assertTrue("legacy row missing post-migration", it.moveToFirst())
            assertEquals(111L, it.getLong(0))
        }
        db.query("SELECT DOWNLOAD_STATE, DOWNLOAD_TIME FROM ARCHIVE_LOCAL_STATE WHERE ARCID='shared' AND SERVER_PROFILE_ID=10").use {
            assertTrue("download row missing post-migration", it.moveToFirst())
            assertEquals(2L, it.getLong(0))
            assertEquals(222L, it.getLong(1))
        }

        // a second profile can now coexist for the same arcid
        db.execSQL(
            "INSERT INTO ARCHIVE_LOCAL_STATE (ARCID, SERVER_PROFILE_ID, ARCHIVE_JSON, HISTORY_TIME) " +
                "VALUES ('shared', 20, '{\"arcid\":\"shared\"}', 333)"
        )
        db.query("SELECT COUNT(*) FROM ARCHIVE_LOCAL_STATE WHERE ARCID='shared'").use {
            assertTrue(it.moveToFirst())
            assertEquals(2, it.getInt(0))
        }

        // all five secondary indices recreated
        val idx = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='ARCHIVE_LOCAL_STATE'").use {
            while (it.moveToNext()) idx.add(it.getString(0))
        }
        listOf("SERVER_PROFILE_ID", "DOWNLOAD_TIME", "HISTORY_TIME", "FAVORITE_TIME", "DOWNLOAD_LABEL").forEach {
            assertTrue("missing index_ARCHIVE_LOCAL_STATE_$it", "index_ARCHIVE_LOCAL_STATE_$it" in idx)
        }
    }

    @Test
    fun room_opensCleanly_afterRealUpgrade_v26_to_v27() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "db3_spike_v26.db"
        ctx.getDatabasePath(name).also { it.parentFile?.mkdirs(); it.delete() }

        // 1) Build a raw v26 DB FILE with ALL FIVE v26 tables (Room validates every
        //    table). createSql copied verbatim from app/schemas/.../26.json.
        val v26Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(26) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `DOWNLOAD_LABELS` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT, `LABEL` TEXT, `TIME` INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_DOWNLOAD_LABELS_TIME` ON `DOWNLOAD_LABELS` (`TIME`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `DOWNLOAD_DIRNAME` (`ARCID` TEXT NOT NULL, `DIRNAME` TEXT, PRIMARY KEY(`ARCID`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `QUICK_SEARCH` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT, `NAME` TEXT, `MODE` INTEGER NOT NULL, `CATEGORY` INTEGER NOT NULL, `KEYWORD` TEXT, `ADVANCE_SEARCH` INTEGER NOT NULL, `MIN_RATING` INTEGER NOT NULL, `PAGE_FROM` INTEGER NOT NULL, `PAGE_TO` INTEGER NOT NULL, `TIME` INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_QUICK_SEARCH_TIME` ON `QUICK_SEARCH` (`TIME`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `SERVER_PROFILES` (`ID` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `NAME` TEXT NOT NULL, `URL` TEXT NOT NULL, `IS_ACTIVE` INTEGER NOT NULL, `ALLOW_CLEARTEXT` INTEGER NOT NULL DEFAULT 1)")
                        createV26ArchiveLocalState(db)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        // unused
                    }
                }).build()
        )
        v26Helper.writableDatabase.execSQL(
            "INSERT INTO ARCHIVE_LOCAL_STATE (ARCID, SERVER_PROFILE_ID, ARCHIVE_JSON, DOWNLOAD_STATE) " +
                "VALUES ('a', 0, '{\"arcid\":\"a\"}', 2)"
        )
        v26Helper.close()

        // 2) Open via Room with the migration chain -> runs onUpgrade +
        //    validateMigration. Room opens at the CURRENT schema version, so
        //    every migration from v26 onward must be present.
        val room = Room.databaseBuilder(ctx, AppDatabase::class.java, name)
            .addMigrations(
                AppDatabase.MIGRATION_26_27,
                AppDatabase.MIGRATION_27_28,
                AppDatabase.MIGRATION_28_29,
                AppDatabase.MIGRATION_29_30,
            )
            .build()
        try {
            // Forces the open helper to run the migration + schema validation;
            // throws IllegalStateException if Room rejects the migrated schema.
            room.openHelper.writableDatabase

            // Sanity: the composite key lets a second profile coexist for arcid 'a'.
            runBlocking {
                room.archiveLocalStateDao().insertOrIgnoreHistory("a", 9L, "{\"arcid\":\"a\"}", 1L, 0)
                room.archiveLocalStateDao().updateHistoryFields("a", 9L, "{\"arcid\":\"a\"}", 1L, 0)
                assertEquals(1, room.archiveLocalStateDao().getAllDownloads().size) // (a, 0) download
                assertEquals(1, room.archiveLocalStateDao().getAllHistory().size)   // (a, 9) history
            }
        } finally {
            room.close()
            ctx.getDatabasePath(name).delete()
        }
    }

    private companion object {
        /** v26 ARCHIVE_LOCAL_STATE table + its five indices, copied from 26.json. */
        fun createV26ArchiveLocalState(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `ARCHIVE_LOCAL_STATE` (" +
                    "`ARCID` TEXT NOT NULL, `SERVER_PROFILE_ID` INTEGER NOT NULL DEFAULT 0, " +
                    "`ARCHIVE_JSON` TEXT NOT NULL, `DOWNLOAD_STATE` INTEGER, " +
                    "`DOWNLOAD_LEGACY` INTEGER NOT NULL DEFAULT 0, `DOWNLOAD_TIME` INTEGER, " +
                    "`DOWNLOAD_LABEL` TEXT, `DOWNLOAD_ARCHIVE_URI` TEXT, `DOWNLOAD_ROOT_URI` TEXT, " +
                    "`HISTORY_TIME` INTEGER, `HISTORY_MODE` INTEGER NOT NULL DEFAULT 0, " +
                    "`HISTORY_SCROLL_FRACTION` REAL, `FAVORITE_TIME` INTEGER, PRIMARY KEY(`ARCID`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_SERVER_PROFILE_ID` ON `ARCHIVE_LOCAL_STATE` (`SERVER_PROFILE_ID`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_DOWNLOAD_TIME` ON `ARCHIVE_LOCAL_STATE` (`DOWNLOAD_TIME`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_HISTORY_TIME` ON `ARCHIVE_LOCAL_STATE` (`HISTORY_TIME`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_FAVORITE_TIME` ON `ARCHIVE_LOCAL_STATE` (`FAVORITE_TIME`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ARCHIVE_LOCAL_STATE_DOWNLOAD_LABEL` ON `ARCHIVE_LOCAL_STATE` (`DOWNLOAD_LABEL`)")
        }
    }
}
