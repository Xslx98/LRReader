package com.lanraragi.reader.util

import android.content.res.Resources
import com.lanraragi.reader.R

object ReadableTime {

    private const val SECOND_MILLIS = 1000L
    private const val MINUTE_MILLIS = 60 * SECOND_MILLIS
    private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
    private const val DAY_MILLIS = 24 * HOUR_MILLIS
    private const val YEAR_MILLIS = 365 * DAY_MILLIS

    // Largest unit first; a unit is used once the interval exceeds 1.5 of it.
    private val MULTIPLES = longArrayOf(YEAR_MILLIS, DAY_MILLIS, HOUR_MILLIS, MINUTE_MILLIS, SECOND_MILLIS)
    private val UNITS = intArrayOf(R.plurals.year, R.plurals.day, R.plurals.hour, R.plurals.minute, R.plurals.second)

    /** [millis] in its largest fitting unit, e.g. "3 minutes" (the download ETA). */
    fun shortInterval(resources: Resources, millis: Long): String {
        val i = MULTIPLES.indices.firstOrNull { millis > MULTIPLES[it] * 1.5 } ?: MULTIPLES.lastIndex
        val quotient = millis / MULTIPLES[i]
        return "$quotient ${resources.getQuantityString(UNITS[i], quotient.toInt())}"
    }
}
