package com.lanraragi.reader.dao

import android.util.Log
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * DB-level backstop for the "at most one download row per arcid" invariant
 * (audit 2026-10-04 C45).
 *
 * ADR-003 keeps the invariant in the app layer because Room validates
 * indices by strict set equality and would reject an undeclared partial
 * unique index. Room does not validate triggers, so two `CREATE TRIGGER IF
 * NOT EXISTS` statements installed on every open enforce it without a
 * schema bump: a write that would give an arcid a download row under a
 * second profile aborts with `SQLiteConstraintException` instead of silently
 * creating two rows that share one directory and one progress key.
 *
 * A database that already holds a violation gets no triggers (any later
 * write to either row would then abort); the violation is logged and the
 * app-layer guard keeps working as before.
 */
object DownloadArcidGuard : RoomDatabase.Callback() {
    private const val TAG = "DownloadArcidGuard"

    const val INSERT_TRIGGER = "lrr_uniq_download_arcid_insert"
    const val UPDATE_TRIGGER = "lrr_uniq_download_arcid_update"
    const val ABORT_MESSAGE = "duplicate download arcid"

    private const val CONFLICT =
        "NEW.DOWNLOAD_STATE IS NOT NULL AND EXISTS (SELECT 1 FROM ARCHIVE_LOCAL_STATE " +
            "WHERE ARCID = NEW.ARCID AND SERVER_PROFILE_ID <> NEW.SERVER_PROFILE_ID " +
            "AND DOWNLOAD_STATE IS NOT NULL)"

    override fun onOpen(db: SupportSQLiteDatabase) {
        install(db)
    }

    /**
     * Installs both triggers unless the table already violates the invariant.
     *
     * @return the arcids that have download rows under more than one profile
     *   (empty when the triggers were installed).
     */
    fun install(db: SupportSQLiteDatabase): List<String> {
        val duplicates = findDuplicates(db)
        if (duplicates.isNotEmpty()) {
            Log.e(TAG, "${duplicates.size} arcid(s) have download rows under several profiles; trigger not installed")
            return duplicates
        }
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS `$INSERT_TRIGGER` BEFORE INSERT ON ARCHIVE_LOCAL_STATE " +
                "WHEN $CONFLICT BEGIN SELECT RAISE(ABORT, '$ABORT_MESSAGE'); END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS `$UPDATE_TRIGGER` " +
                "BEFORE UPDATE OF DOWNLOAD_STATE, ARCID, SERVER_PROFILE_ID ON ARCHIVE_LOCAL_STATE " +
                "WHEN $CONFLICT BEGIN SELECT RAISE(ABORT, '$ABORT_MESSAGE'); END"
        )
        return duplicates
    }

    internal fun findDuplicates(db: SupportSQLiteDatabase): List<String> =
        db.query(
            "SELECT ARCID FROM ARCHIVE_LOCAL_STATE WHERE DOWNLOAD_STATE IS NOT NULL " +
                "GROUP BY ARCID HAVING COUNT(*) > 1"
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
}
