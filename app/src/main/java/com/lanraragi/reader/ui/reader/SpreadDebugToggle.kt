package com.lanraragi.reader.ui.reader

import android.content.Context
import com.lanraragi.framework.lib.glgallery.GalleryView
import com.lanraragi.reader.BuildConfig
import java.io.File
import java.io.IOException

/**
 * The hidden switch for two-page spreads until they get a reading setting
 * (plan item 20, PR D). Debug builds only; the reader reads it when it opens
 * and when the settings dialog applies a change:
 *
 * `adb shell "echo landscape > /sdcard/Android/data/com.lanraragi.reader.debug/files/spread-reading"`
 *
 * `landscape` pairs pages while the reader is wider than tall, `always` in
 * any shape; a missing file or anything else is off. Release builds never
 * call this, so they keep single pages.
 */
internal object SpreadDebugToggle {
    private const val FILE_NAME = "spread-reading"

    fun read(context: Context): Int {
        if (!BuildConfig.DEBUG) return GalleryView.SPREAD_OFF
        val dir = context.getExternalFilesDir(null) ?: return GalleryView.SPREAD_OFF
        val word = try {
            File(dir, FILE_NAME).takeIf { it.isFile }?.readText()?.trim()
        } catch (ignored: IOException) {
            // Unreadable reads as off.
            null
        }
        return when (word) {
            "landscape" -> GalleryView.SPREAD_LANDSCAPE
            "always" -> GalleryView.SPREAD_ALWAYS
            else -> GalleryView.SPREAD_OFF
        }
    }
}
