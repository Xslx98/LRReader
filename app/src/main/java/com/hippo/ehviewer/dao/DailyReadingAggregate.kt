package com.hippo.ehviewer.dao

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * One day's reading activity for one server profile (issue #20): the
 * pages-read delta and completed count accumulated at reading-session end.
 * No UI consumes it yet — it exists so future trend features have history
 * from today onward. Reading DURATION is permanently out of scope (triage
 * decision) — pages are the proxy metric.
 */
@Entity(
    tableName = "DAILY_READING_AGGREGATE",
    primaryKeys = ["EPOCH_DAY", "SERVER_PROFILE_ID"],
    indices = [Index("SERVER_PROFILE_ID", "EPOCH_DAY")]
)
class DailyReadingAggregate(
    /** LocalDate.toEpochDay() of the (device-local) reading day. */
    @ColumnInfo(name = "EPOCH_DAY")
    @JvmField
    var epochDay: Long = 0,

    @ColumnInfo(name = "SERVER_PROFILE_ID")
    @JvmField
    var serverProfileId: Long = 0,

    @ColumnInfo(name = "PAGES_READ")
    @JvmField
    var pagesRead: Long = 0,

    @ColumnInfo(name = "COMPLETED")
    @JvmField
    var completed: Int = 0
)
