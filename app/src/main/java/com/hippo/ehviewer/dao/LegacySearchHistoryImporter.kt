package com.hippo.ehviewer.dao

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.withTransaction

/**
 * One-time lift of the retired legacy EhViewer search history
 * (`search_database.db`, table `suggestions`) into the per-profile Room
 * SEARCH_HISTORY store (issue #12).
 *
 * Semantics:
 * - imports the [SearchHistoryRepository.MAX_ENTRIES] most-recent legacy rows
 *   into [activeProfileId], keeping their original dates so recency ordering
 *   survives the lift;
 * - INSERT OR IGNORE — an entry the user already re-created post-migration
 *   keeps its fresher timestamp;
 * - the legacy file is deleted only after a completed import; on failure or
 *   when no active profile exists yet the file stays and the import retries
 *   on a later boot.
 */
object LegacySearchHistoryImporter {

    private const val TAG = "LegacyHistoryImport"
    private const val LEGACY_DB = "search_database.db"

    suspend fun importIfPresent(context: Context, db: AppDatabase, activeProfileId: Long) {
        if (activeProfileId <= 0) return
        val file = context.getDatabasePath(LEGACY_DB)
        if (!file.exists()) return

        val rows = mutableListOf<SearchHistoryEntry>()
        try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { legacy ->
                legacy.rawQuery(
                    "SELECT query, date FROM suggestions ORDER BY date DESC LIMIT " +
                        SearchHistoryRepository.MAX_ENTRIES,
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        val query = c.getString(0)?.trim().orEmpty()
                        if (query.isNotEmpty()) {
                            rows.add(SearchHistoryEntry(query, activeProfileId, c.getLong(1)))
                        }
                    }
                }
            }
            val dao = db.browsingDao()
            db.withTransaction {
                for (row in rows) dao.insertSearchHistoryIfAbsent(row)
                dao.evictSearchHistoryBeyond(activeProfileId, SearchHistoryRepository.MAX_ENTRIES)
            }
        } catch (e: Exception) {
            Log.w(TAG, "legacy search-history import failed; will retry next boot")
            return
        }
        context.deleteDatabase(LEGACY_DB)
    }
}
