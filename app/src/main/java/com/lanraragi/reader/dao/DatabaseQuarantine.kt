package com.lanraragi.reader.dao

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File

/**
 * Moves a broken database aside instead of deleting it (audit 2026-10-04 C04).
 *
 * Both recovery paths used to destroy the user's library: the boot-failure
 * "Reset database" button called `Context.deleteDatabase`, and SQLite
 * corruption went to the platform's default `onCorruption`, which deletes
 * the file. Now the main file and its `-wal`/`-shm`/`-journal` siblings are
 * renamed to `<name>.broken-<millis><suffix>`, so a quarantined set still
 * opens as one database (`eh.db.broken-<millis>` finds its own `-wal`) and
 * can be inspected or restored by hand. Only the newest [KEEP] sets are kept.
 */
object DatabaseQuarantine {
    private const val TAG = "DatabaseQuarantine"

    /** Infix between the database name and the timestamp of a quarantined set. */
    const val MARKER = ".broken-"

    /** Quarantined sets kept per database; older sets are deleted. */
    const val KEEP = 3

    private val SIBLING_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

    /**
     * Renames [dbFile] and its siblings aside and prunes old sets. A file that
     * cannot be renamed is deleted so the caller still gets a fresh database.
     *
     * @return the quarantined main file, or null when nothing was moved.
     */
    fun quarantine(dbFile: File, nowMillis: Long = System.currentTimeMillis(), keep: Int = KEEP): File? {
        val dir = dbFile.absoluteFile.parentFile ?: return null
        var movedMain: File? = null
        for (suffix in SIBLING_SUFFIXES) {
            val src = File(dir, dbFile.name + suffix)
            if (!src.exists()) continue
            val dst = File(dir, dbFile.name + MARKER + nowMillis + suffix)
            if (src.renameTo(dst)) {
                if (suffix.isEmpty()) movedMain = dst
            } else {
                Log.e(TAG, "rename failed, deleting ${src.name}")
                src.delete()
            }
        }
        prune(dir, dbFile.name, keep)
        return movedMain
    }

    /** Deletes every quarantined set of [dbName] in [dir] except the newest [keep]. */
    internal fun prune(dir: File, dbName: String, keep: Int) {
        val prefix = dbName + MARKER
        val byStamp = dir.listFiles().orEmpty()
            .filter { it.name.startsWith(prefix) }
            .groupBy { it.name.removePrefix(prefix).takeWhile(Char::isDigit).toLongOrNull() }
        byStamp.keys.filterNotNull().sortedDescending().drop(keep).forEach { stamp ->
            byStamp.getValue(stamp).forEach { it.delete() }
        }
    }
}

/**
 * Open-helper factory whose callback quarantines a corrupt database instead
 * of letting the platform default delete it. Everything else is delegated.
 */
class QuarantiningOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory()
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        delegate.create(
            SupportSQLiteOpenHelper.Configuration(
                configuration.context,
                configuration.name,
                QuarantiningCallback(configuration.callback),
                configuration.useNoBackupDirectory,
                configuration.allowDataLossOnRecovery
            )
        )

    private class QuarantiningCallback(
        private val delegate: SupportSQLiteOpenHelper.Callback
    ) : SupportSQLiteOpenHelper.Callback(delegate.version) {
        override fun onConfigure(db: SupportSQLiteDatabase) = delegate.onConfigure(db)
        override fun onCreate(db: SupportSQLiteDatabase) = delegate.onCreate(db)
        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            delegate.onUpgrade(db, oldVersion, newVersion)
        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            delegate.onDowngrade(db, oldVersion, newVersion)
        override fun onOpen(db: SupportSQLiteDatabase) = delegate.onOpen(db)

        override fun onCorruption(db: SupportSQLiteDatabase) {
            val path = db.path
            if (path.isNullOrEmpty() || path == ":memory:") {
                delegate.onCorruption(db)
                return
            }
            Log.e(TAG, "database corruption detected, quarantining $path")
            try {
                db.close()
            } catch (e: Exception) {
                Log.e(TAG, "close before quarantine failed", e)
            }
            DatabaseQuarantine.quarantine(File(path))
        }
    }

    private companion object {
        const val TAG = "DatabaseQuarantine"
    }
}
