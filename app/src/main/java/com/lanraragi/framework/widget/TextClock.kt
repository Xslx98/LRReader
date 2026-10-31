/*
 * Copyright (C) 2014 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lanraragi.framework.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.ViewDebug.ExportedProperty
import androidx.appcompat.widget.AppCompatTextView
import java.util.Calendar
import java.util.TimeZone

class TextClock : AppCompatTextView {

    var format12Hour: CharSequence? = DEFAULT_FORMAT_12_HOUR
        set(value) {
            field = value

            chooseFormat()
            onTimeChanged()
        }

    var format24Hour: CharSequence? = DEFAULT_FORMAT_24_HOUR
        set(value) {
            field = value

            chooseFormat()
            onTimeChanged()
        }

    @field:ExportedProperty
    var format: CharSequence? = null
        private set

    @field:ExportedProperty
    private var hasSeconds = false

    private var attached = false

    private lateinit var time: Calendar

    var timeZone: String? = TimeZone.getDefault().id
        set(value) {
            field = value

            createTime(value)
            onTimeChanged()
        }

    private val formatChangeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            chooseFormat()
            onTimeChanged()
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            chooseFormat()
            onTimeChanged()
        }
    }

    private val intentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (timeZone == null && Intent.ACTION_TIMEZONE_CHANGED == intent.action) {
                val newTimeZone = intent.getStringExtra("time-zone")
                createTime(newTimeZone)
            }
            onTimeChanged()
        }
    }

    private val ticker: Runnable = object : Runnable {
        override fun run() {
            onTimeChanged()

            val now = SystemClock.uptimeMillis()
            val next = now + (1000 - now % 1000)

            handler.postAtTime(this, next)
        }
    }

    constructor(context: Context) : this(context, null)

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) {
        init()
    }

    private fun init() {
        createTime(timeZone)
        // Wait until onAttachedToWindow() to handle the ticker
        chooseFormat(false)
    }

    private fun createTime(timeZone: String?) {
        time = if (timeZone != null) {
            Calendar.getInstance(TimeZone.getTimeZone(timeZone))
        } else {
            Calendar.getInstance()
        }
    }

    fun is24HourModeEnabled(): Boolean = DateFormat.is24HourFormat(context)

    /**
     * Selects either one of [format12Hour] or [format24Hour]
     * depending on whether the user has selected 24-hour format.
     *
     * Calling this method does not schedule or unschedule the time ticker.
     */
    private fun chooseFormat() {
        chooseFormat(true)
    }

    /**
     * Selects either one of [format12Hour] or [format24Hour]
     * depending on whether the user has selected 24-hour format.
     *
     * @param handleTicker true if calling this method should schedule/unschedule the
     *                     time ticker, false otherwise
     */
    private fun chooseFormat(handleTicker: Boolean) {
        val format24Requested = is24HourModeEnabled()

        format = if (format24Requested) {
            format24Hour
        } else {
            format12Hour
        }

        val hadSeconds = hasSeconds
        hasSeconds = formatHasSeconds(format)

        if (handleTicker && attached && hadSeconds != hasSeconds) {
            if (hadSeconds) handler.removeCallbacks(ticker) else ticker.run()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (!attached) {
            attached = true

            registerReceiver()
            registerObserver()

            createTime(timeZone)

            if (hasSeconds) {
                ticker.run()
            } else {
                onTimeChanged()
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        if (attached) {
            unregisterReceiver()
            unregisterObserver()

            handler.removeCallbacks(ticker)

            attached = false
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter()

        filter.addAction(Intent.ACTION_TIME_TICK)
        filter.addAction(Intent.ACTION_TIME_CHANGED)
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED)

        context.registerReceiver(intentReceiver, filter, null, handler)
    }

    private fun registerObserver() {
        val resolver = context.contentResolver
        resolver.registerContentObserver(Settings.System.CONTENT_URI, true, formatChangeObserver)
    }

    private fun unregisterReceiver() {
        context.unregisterReceiver(intentReceiver)
    }

    private fun unregisterObserver() {
        val resolver = context.contentResolver
        resolver.unregisterContentObserver(formatChangeObserver)
    }

    private fun onTimeChanged() {
        time.timeInMillis = System.currentTimeMillis()
        text = DateFormat.format(format, time)
    }

    companion object {
        const val DEFAULT_FORMAT_12_HOUR = "hh:mm a"

        const val DEFAULT_FORMAT_24_HOUR = "HH:mm"
    }
}

private const val QUOTE = '\''
private const val SECONDS = 's'

// Folded in from the package-private DateUtils, whose only user this was.
private fun formatHasSeconds(inFormat: CharSequence?): Boolean = hasDesignator(inFormat, SECONDS)

private fun hasDesignator(inFormat: CharSequence?, designator: Char): Boolean {
    if (inFormat == null) return false

    val length = inFormat.length

    var c: Char
    var count: Int

    var i = 0
    while (i < length) {
        count = 1
        c = inFormat[i]

        if (c == QUOTE) {
            count = skipQuotedText(inFormat, i, length)
        } else if (c == designator) {
            return true
        }
        i += count
    }

    return false
}

private fun skipQuotedText(s: CharSequence, start: Int, len: Int): Int {
    var i = start
    if (i + 1 < len && s[i + 1] == QUOTE) {
        return 2
    }

    var count = 1
    // skip leading quote
    i++

    while (i < len) {
        val c = s[i]

        if (c == QUOTE) {
            count++
            //  QUOTEQUOTE -> QUOTE
            if (i + 1 < len && s[i + 1] == QUOTE) {
                i++
            } else {
                break
            }
        } else {
            i++
            count++
        }
    }

    return count
}
