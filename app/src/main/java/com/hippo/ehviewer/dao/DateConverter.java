package com.hippo.ehviewer.dao;

import androidx.room.TypeConverter;

import java.util.Date;

/**
 * Room TypeConverter for java.util.Date ↔ Long (epoch millis).
 * GreenDAO stored Date as INTEGER (millis) — this converter maintains compatibility.
 */
public class DateConverter {

    @TypeConverter
    public static Date fromTimestamp(Long value) {
        return value == null ? null : new Date(value);
    }

    @TypeConverter
    public static Long dateToTimestamp(Date date) {
        return date == null ? null : date.getTime();
    }
}
