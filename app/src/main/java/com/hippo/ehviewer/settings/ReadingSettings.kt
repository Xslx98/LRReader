package com.hippo.ehviewer.settings

import com.hippo.ehviewer.Settings
import com.hippo.lib.glgallery.GalleryView

/**
 * Reading-related settings extracted from Settings.java.
 * Covers screen rotation, reading direction, page scaling, brightness, overlays, etc.
 *
 * Callers should migrate from Settings.getXxx() to ReadingSettings.xxx over time.
 */
object ReadingSettings {

    // --- Screen Rotation ---
    const val KEY_SCREEN_ROTATION = "screen_rotation"
    private const val DEFAULT_SCREEN_ROTATION = 0

    @JvmStatic
    fun getScreenRotation(): Int = Settings.getIntFromStr(KEY_SCREEN_ROTATION, DEFAULT_SCREEN_ROTATION)

    @JvmStatic
    fun putScreenRotation(value: Int) = Settings.putIntToStr(KEY_SCREEN_ROTATION, value)

    // --- Reading Direction ---
    const val KEY_READING_DIRECTION = "reading_direction"
    private val DEFAULT_READING_DIRECTION = GalleryView.LAYOUT_RIGHT_TO_LEFT

    @JvmStatic
    @GalleryView.LayoutMode
    fun getReadingDirection(): Int =
        GalleryView.sanitizeLayoutMode(Settings.getIntFromStr(KEY_READING_DIRECTION, DEFAULT_READING_DIRECTION))

    @JvmStatic
    fun putReadingDirection(value: Int) = Settings.putIntToStr(KEY_READING_DIRECTION, value)

    // --- Page Scaling ---
    const val KEY_PAGE_SCALING = "page_scaling"
    private val DEFAULT_PAGE_SCALING = GalleryView.SCALE_FIT

    @JvmStatic
    @GalleryView.ScaleMode
    fun getPageScaling(): Int =
        GalleryView.sanitizeScaleMode(Settings.getIntFromStr(KEY_PAGE_SCALING, DEFAULT_PAGE_SCALING))

    @JvmStatic
    fun putPageScaling(value: Int) = Settings.putIntToStr(KEY_PAGE_SCALING, value)

    // --- Start Position ---
    const val KEY_START_POSITION = "start_position"
    private val DEFAULT_START_POSITION = GalleryView.START_POSITION_TOP_RIGHT

    @JvmStatic
    @GalleryView.StartPosition
    fun getStartPosition(): Int =
        GalleryView.sanitizeStartPosition(Settings.getIntFromStr(KEY_START_POSITION, DEFAULT_START_POSITION))

    @JvmStatic
    fun putStartPosition(value: Int) = Settings.putIntToStr(KEY_START_POSITION, value)

    // --- Page Turn Transfer Time ---
    const val KEY_START_TRANSFER_TIME = "start_transfer_time"
    private const val DEFAULT_START_TRANSFER_TIME = 2

    @JvmStatic
    fun getStartTransferTime(): Int = Settings.getInt(KEY_START_TRANSFER_TIME, DEFAULT_START_TRANSFER_TIME)

    @JvmStatic
    fun putStartTransferTime(value: Int) = Settings.putInt(KEY_START_TRANSFER_TIME, value)

    // --- Keep Screen On ---
    const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val DEFAULT_KEEP_SCREEN_ON = false

    @JvmStatic
    fun getKeepScreenOn(): Boolean = Settings.getBoolean(KEY_KEEP_SCREEN_ON, DEFAULT_KEEP_SCREEN_ON)

    @JvmStatic
    fun putKeepScreenOn(value: Boolean) = Settings.putBoolean(KEY_KEEP_SCREEN_ON, value)

    // --- Show Clock ---
    const val KEY_SHOW_CLOCK = "gallery_show_clock"
    private const val DEFAULT_SHOW_CLOCK = true

    @JvmStatic
    fun getShowClock(): Boolean = Settings.getBoolean(KEY_SHOW_CLOCK, DEFAULT_SHOW_CLOCK)

    @JvmStatic
    fun putShowClock(value: Boolean) = Settings.putBoolean(KEY_SHOW_CLOCK, value)

    // --- Show Progress ---
    const val KEY_SHOW_PROGRESS = "gallery_show_progress"
    private const val DEFAULT_SHOW_PROGRESS = true

    @JvmStatic
    fun getShowProgress(): Boolean = Settings.getBoolean(KEY_SHOW_PROGRESS, DEFAULT_SHOW_PROGRESS)

    @JvmStatic
    fun putShowProgress(value: Boolean) = Settings.putBoolean(KEY_SHOW_PROGRESS, value)

    // --- Show Battery ---
    const val KEY_SHOW_BATTERY = "gallery_show_battery"
    private const val DEFAULT_SHOW_BATTERY = true

    @JvmStatic
    fun getShowBattery(): Boolean = Settings.getBoolean(KEY_SHOW_BATTERY, DEFAULT_SHOW_BATTERY)

    @JvmStatic
    fun putShowBattery(value: Boolean) = Settings.putBoolean(KEY_SHOW_BATTERY, value)

    // --- Show Page Interval ---
    const val KEY_SHOW_PAGE_INTERVAL = "gallery_show_page_interval"
    private const val DEFAULT_SHOW_PAGE_INTERVAL = true

    @JvmStatic
    fun getShowPageInterval(): Boolean = Settings.getBoolean(KEY_SHOW_PAGE_INTERVAL, DEFAULT_SHOW_PAGE_INTERVAL)

    @JvmStatic
    fun putShowPageInterval(value: Boolean) = Settings.putBoolean(KEY_SHOW_PAGE_INTERVAL, value)

    // --- Volume Page ---
    const val KEY_VOLUME_PAGE = "volume_page"
    private const val DEFAULT_VOLUME_PAGE = false

    @JvmStatic
    fun getVolumePage(): Boolean = Settings.getBoolean(KEY_VOLUME_PAGE, DEFAULT_VOLUME_PAGE)

    @JvmStatic
    fun putVolumePage(value: Boolean) = Settings.putBoolean(KEY_VOLUME_PAGE, value)

    // --- Reverse Volume Page ---
    const val KEY_REVERSE_VOLUME_PAGE = "reverse_volume_page"
    private const val DEFAULT_REVERSE_VOLUME_PAGE = false

    @JvmStatic
    fun getReverseVolumePage(): Boolean = Settings.getBoolean(KEY_REVERSE_VOLUME_PAGE, DEFAULT_REVERSE_VOLUME_PAGE)

    @JvmStatic
    fun putReverseVolumePage(value: Boolean) = Settings.putBoolean(KEY_REVERSE_VOLUME_PAGE, value)

    // --- Reading Fullscreen ---
    const val KEY_READING_FULLSCREEN = "reading_fullscreen"
    private const val DEFAULT_READING_FULLSCREEN = true

    @JvmStatic
    fun getReadingFullscreen(): Boolean = Settings.getBoolean(KEY_READING_FULLSCREEN, DEFAULT_READING_FULLSCREEN)

    @JvmStatic
    fun putReadingFullscreen(value: Boolean) = Settings.putBoolean(KEY_READING_FULLSCREEN, value)

    // --- Custom Screen Lightness ---
    const val KEY_CUSTOM_SCREEN_LIGHTNESS = "custom_screen_lightness"
    private const val DEFAULT_CUSTOM_SCREEN_LIGHTNESS = false

    @JvmStatic
    fun getCustomScreenLightness(): Boolean = Settings.getBoolean(KEY_CUSTOM_SCREEN_LIGHTNESS, DEFAULT_CUSTOM_SCREEN_LIGHTNESS)

    @JvmStatic
    fun putCustomScreenLightness(value: Boolean) = Settings.putBoolean(KEY_CUSTOM_SCREEN_LIGHTNESS, value)

    // --- Screen Lightness ---
    const val KEY_SCREEN_LIGHTNESS = "screen_lightness"
    private const val DEFAULT_SCREEN_LIGHTNESS = 50

    @JvmStatic
    fun getScreenLightness(): Int = Settings.getInt(KEY_SCREEN_LIGHTNESS, DEFAULT_SCREEN_LIGHTNESS)

    @JvmStatic
    fun putScreenLightness(value: Int) = Settings.putInt(KEY_SCREEN_LIGHTNESS, value)

    // --- Read Cache Size ---
    const val KEY_READ_CACHE_SIZE = "read_cache_size"
    const val DEFAULT_READ_CACHE_SIZE = 160

    @JvmStatic
    fun getReadCacheSize(): Int = Settings.getIntFromStr(KEY_READ_CACHE_SIZE, DEFAULT_READ_CACHE_SIZE)

    // --- Show Read Progress ---
    const val KEY_SHOW_READ_PROGRESS = "show_read_progress"
    private const val DEFAULT_SHOW_READ_PROGRESS = true

    @JvmStatic
    fun getShowReadProgress(): Boolean = Settings.getBoolean(KEY_SHOW_READ_PROGRESS, DEFAULT_SHOW_READ_PROGRESS)

    @JvmStatic
    fun setShowReadProgress(value: Boolean) = Settings.putBoolean(KEY_SHOW_READ_PROGRESS, value)
}
