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

@file:Suppress("MayBeConst") // TODO(2.4-idiom)

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

    private var mFormat12: CharSequence? = DEFAULT_FORMAT_12_HOUR
    private var mFormat24: CharSequence? = DEFAULT_FORMAT_24_HOUR

    @field:ExportedProperty
    private var mFormat: CharSequence? = null

    @field:ExportedProperty
    private var mHasSeconds = false

    private var mAttached = false

    private lateinit var mTime: Calendar
    private var mTimeZone: String? = TimeZone.getDefault().id

    private val mFormatChangeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            chooseFormat()
            onTimeChanged()
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            chooseFormat()
            onTimeChanged()
        }
    }

    private val mIntentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (mTimeZone == null && Intent.ACTION_TIMEZONE_CHANGED == intent.action) {
                val timeZone = intent.getStringExtra("time-zone")
                createTime(timeZone)
            }
            onTimeChanged()
        }
    }

    private val mTicker: Runnable = object : Runnable {
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
        createTime(mTimeZone)
        // Wait until onAttachedToWindow() to handle the ticker
        chooseFormat(false)
    }

    private fun createTime(timeZone: String?) {
        mTime = if (timeZone != null) {
            Calendar.getInstance(TimeZone.getTimeZone(timeZone))
        } else {
            Calendar.getInstance()
        }
    }

    fun getFormat12Hour(): CharSequence? = mFormat12

    fun setFormat12Hour(format: CharSequence?) {
        mFormat12 = format

        chooseFormat()
        onTimeChanged()
    }

    fun getFormat24Hour(): CharSequence? = mFormat24

    fun setFormat24Hour(format: CharSequence?) {
        mFormat24 = format

        chooseFormat()
        onTimeChanged()
    }

    fun is24HourModeEnabled(): Boolean = DateFormat.is24HourFormat(context)

    fun getTimeZone(): String? = mTimeZone

    fun setTimeZone(timeZone: String?) {
        mTimeZone = timeZone

        createTime(timeZone)
        onTimeChanged()
    }

    /**
     * Selects either one of [getFormat12Hour] or [getFormat24Hour]
     * depending on whether the user has selected 24-hour format.
     *
     * Calling this method does not schedule or unschedule the time ticker.
     */
    private fun chooseFormat() {
        chooseFormat(true)
    }

    fun getFormat(): CharSequence? = mFormat

    /**
     * Selects either one of [getFormat12Hour] or [getFormat24Hour]
     * depending on whether the user has selected 24-hour format.
     *
     * @param handleTicker true if calling this method should schedule/unschedule the
     *                     time ticker, false otherwise
     */
    private fun chooseFormat(handleTicker: Boolean) {
        val format24Requested = is24HourModeEnabled()

        mFormat = if (format24Requested) {
            mFormat24
        } else {
            mFormat12
        }

        val hadSeconds = mHasSeconds
        mHasSeconds = hasSeconds(mFormat)

        if (handleTicker && mAttached && hadSeconds != mHasSeconds) {
            if (hadSeconds) handler.removeCallbacks(mTicker) else mTicker.run()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (!mAttached) {
            mAttached = true

            registerReceiver()
            registerObserver()

            createTime(mTimeZone)

            if (mHasSeconds) {
                mTicker.run()
            } else {
                onTimeChanged()
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        if (mAttached) {
            unregisterReceiver()
            unregisterObserver()

            handler.removeCallbacks(mTicker)

            mAttached = false
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter()

        filter.addAction(Intent.ACTION_TIME_TICK)
        filter.addAction(Intent.ACTION_TIME_CHANGED)
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED)

        context.registerReceiver(mIntentReceiver, filter, null, handler)
    }

    private fun registerObserver() {
        val resolver = context.contentResolver
        resolver.registerContentObserver(Settings.System.CONTENT_URI, true, mFormatChangeObserver)
    }

    private fun unregisterReceiver() {
        context.unregisterReceiver(mIntentReceiver)
    }

    private fun unregisterObserver() {
        val resolver = context.contentResolver
        resolver.unregisterContentObserver(mFormatChangeObserver)
    }

    private fun onTimeChanged() {
        mTime.timeInMillis = System.currentTimeMillis()
        text = DateFormat.format(mFormat, mTime)
    }

    companion object {
        @JvmField
        val DEFAULT_FORMAT_12_HOUR: CharSequence = "hh:mm a"

        @JvmField
        val DEFAULT_FORMAT_24_HOUR: CharSequence = "HH:mm"
    }
}

private const val QUOTE = '\''
private const val SECONDS = 's'

// Folded in from the package-private DateUtils, whose only user this was.
private fun hasSeconds(inFormat: CharSequence?): Boolean = hasDesignator(inFormat, SECONDS)

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
