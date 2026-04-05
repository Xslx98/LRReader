package com.hippo.ehviewer.dao

import androidx.room.TypeConverter
import java.util.Date

object DateConverter {
    @TypeConverter
    @JvmStatic
    fun fromTimestamp(value: Long?): Date? = value?.let { Date(it) }

    @TypeConverter
    @JvmStatic
    fun dateToTimestamp(date: Date?): Long? = date?.time
}
