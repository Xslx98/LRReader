package com.hippo.ehviewer.dao

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * One recorded search query for one server profile ("search history" in
 * CONTEXT.md — automatic, distinct from the user-curated Quick Search).
 *
 * Composite primary key (QUERY, SERVER_PROFILE_ID) makes exact-match dedupe
 * structural: re-recording a query REPLACEs the row, refreshing [lastUsed].
 * The (SERVER_PROFILE_ID, LAST_USED) index serves the recency-ordered
 * per-profile queries.
 */
@Entity(
    tableName = "SEARCH_HISTORY",
    primaryKeys = ["QUERY", "SERVER_PROFILE_ID"],
    indices = [Index("SERVER_PROFILE_ID", "LAST_USED")]
)
class SearchHistoryEntry(
    @ColumnInfo(name = "QUERY")
    @JvmField
    var query: String = "",

    @ColumnInfo(name = "SERVER_PROFILE_ID")
    @JvmField
    var serverProfileId: Long = 0,

    /** Epoch millis of the most recent time this query was searched. */
    @ColumnInfo(name = "LAST_USED")
    @JvmField
    var lastUsed: Long = 0
)
