package com.hippo.ehviewer.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Room DAO for the DAILY_READING_AGGREGATE table (issue #20).
 *
 * Accumulation is insert-or-ignore-then-update — minSdk 28 → SQLite 3.22 has
 * no `ON CONFLICT DO UPDATE` (the L1-migration precedent); callers wrap the
 * pair in a DB transaction.
 */
@Dao
interface StatsRoomDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDailyAggregateIfAbsent(row: DailyReadingAggregate)

    @Query(
        "UPDATE DAILY_READING_AGGREGATE SET " +
            "PAGES_READ = PAGES_READ + :pages, COMPLETED = COMPLETED + :completed " +
            "WHERE EPOCH_DAY = :epochDay AND SERVER_PROFILE_ID = :profileId"
    )
    suspend fun accumulateDailyAggregate(epochDay: Long, profileId: Long, pages: Long, completed: Int)

    @Query(
        "SELECT * FROM DAILY_READING_AGGREGATE WHERE SERVER_PROFILE_ID = :profileId " +
            "ORDER BY EPOCH_DAY DESC"
    )
    suspend fun getDailyAggregatesForProfile(profileId: Long): List<DailyReadingAggregate>

    @Query("SELECT * FROM DAILY_READING_AGGREGATE ORDER BY EPOCH_DAY DESC")
    suspend fun getAllDailyAggregates(): List<DailyReadingAggregate>
}
