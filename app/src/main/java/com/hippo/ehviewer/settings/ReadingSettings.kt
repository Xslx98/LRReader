package com.hippo.ehviewer.settings

import android.content.SharedPreferences
import com.hippo.ehviewer.Settings
import com.hippo.lib.glgallery.GalleryView

/**
 * Reading-related settings extracted from Settings.java.
 * Covers screen rotation, reading direction, page scaling, brightness, overlays, etc.
 *
 * High-frequency settings (queried every frame at 60FPS during gallery reading)
 * are cached in @Volatile fields and kept in sync via OnSharedPreferenceChangeListener.
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

    // --- Reading Direction (hot-path, cached) ---
    const val KEY_READING_DIRECTION = "reading_direction"
    private val DEFAULT_READING_DIRECTION = GalleryView.LAYOUT_RIGHT_TO_LEFT

    @Volatile
    private var cachedReadingDirection: Int = DEFAULT_READING_DIRECTION

    @JvmStatic
    @GalleryView.LayoutMode
    fun getReadingDirection(): Int = cachedReadingDirection

    @JvmStatic
    fun putReadingDirection(value: Int) {
        Settings.putIntToStr(KEY_READING_DIRECTION, value)
        cachedReadingDirection = GalleryView.sanitizeLayoutMode(value)
    }

    // --- Page Scaling (hot-path, cached) ---
    const val KEY_PAGE_SCALING = "page_scaling"
    private val DEFAULT_PAGE_SCALING = GalleryView.SCALE_FIT

    @Volatile
    private var cachedPageScaling: Int = DEFAULT_PAGE_SCALING

    @JvmStatic
    @GalleryView.ScaleMode
    fun getPageScaling(): Int = cachedPageScaling

    @JvmStatic
    fun putPageScaling(value: Int) {
        Settings.putIntToStr(KEY_PAGE_SCALING, value)
        cachedPageScaling = GalleryView.sanitizeScaleMode(value)
    }

    // --- Start Position (hot-path, cached) ---
    const val KEY_START_POSITION = "start_position"
    private val DEFAULT_START_POSITION = GalleryView.START_POSITION_TOP_RIGHT

    @Volatile
    private var cachedStartPosition: Int = DEFAULT_START_POSITION

    @JvmStatic
    @GalleryView.StartPosition
    fun getStartPosition(): Int = cachedStartPosition

    @JvmStatic
    fun putStartPosition(value: Int) {
        Settings.putIntToStr(KEY_START_POSITION, value)
        cachedStartPosition = GalleryView.sanitizeStartPosition(value)
    }

    // --- Page Turn Transfer Time ---
    const val KEY_START_TRANSFER_TIME = "start_transfer_time"
    private const val DEFAULT_START_TRANSFER_TIME = 2

    @JvmStatic
    fun getStartTransferTime(): Int = Settings.getInt(KEY_START_TRANSFER_TIME, DEFAULT_START_TRANSFER_TIME)

    @JvmStatic
    fun putStartTransferTime(value: Int) = Settings.putInt(KEY_START_TRANSFER_TIME, value)

    // --- Keep Screen On (hot-path, cached) ---
    const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val DEFAULT_KEEP_SCREEN_ON = false

    @Volatile
    private var cachedKeepScreenOn: Boolean = DEFAULT_KEEP_SCREEN_ON

    @JvmStatic
    fun getKeepScreenOn(): Boolean = cachedKeepScreenOn

    @JvmStatic
    fun putKeepScreenOn(value: Boolean) {
        Settings.putBoolean(KEY_KEEP_SCREEN_ON, value)
        cachedKeepScreenOn = value
    }

    // --- Show Clock (hot-path, cached) ---
    const val KEY_SHOW_CLOCK = "gallery_show_clock"
    private const val DEFAULT_SHOW_CLOCK = true

    @Volatile
    private var cachedShowClock: Boolean = DEFAULT_SHOW_CLOCK

    @JvmStatic
    fun getShowClock(): Boolean = cachedShowClock

    @JvmStatic
    fun putShowClock(value: Boolean) {
        Settings.putBoolean(KEY_SHOW_CLOCK, value)
        cachedShowClock = value
    }

    // --- Show Progress (hot-path, cached) ---
    const val KEY_SHOW_PROGRESS = "gallery_show_progress"
    private const val DEFAULT_SHOW_PROGRESS = true

    @Volatile
    private var cachedShowProgress: Boolean = DEFAULT_SHOW_PROGRESS

    @JvmStatic
    fun getShowProgress(): Boolean = cachedShowProgress

    @JvmStatic
    fun putShowProgress(value: Boolean) {
        Settings.putBoolean(KEY_SHOW_PROGRESS, value)
        cachedShowProgress = value
    }

    // --- Show Battery (hot-path, cached) ---
    const val KEY_SHOW_BATTERY = "gallery_show_battery"
    private const val DEFAULT_SHOW_BATTERY = true

    @Volatile
    private var cachedShowBattery: Boolean = DEFAULT_SHOW_BATTERY

    @JvmStatic
    fun getShowBattery(): Boolean = cachedShowBattery

    @JvmStatic
    fun putShowBattery(value: Boolean) {
        Settings.putBoolean(KEY_SHOW_BATTERY, value)
        cachedShowBattery = value
    }

    // --- Show Page Interval (hot-path, cached) ---
    const val KEY_SHOW_PAGE_INTERVAL = "gallery_show_page_interval"
    private const val DEFAULT_SHOW_PAGE_INTERVAL = true

    @Volatile
    private var cachedShowPageInterval: Boolean = DEFAULT_SHOW_PAGE_INTERVAL

    @JvmStatic
    fun getShowPageInterval(): Boolean = cachedShowPageInterval

    @JvmStatic
    fun putShowPageInterval(value: Boolean) {
        Settings.putBoolean(KEY_SHOW_PAGE_INTERVAL, value)
        cachedShowPageInterval = value
    }

    // --- Volume Page (not hot-path, checked once at reader init) ---
    const val KEY_VOLUME_PAGE = "volume_page"
    private const val DEFAULT_VOLUME_PAGE = false

    @JvmStatic
    fun getVolumePage(): Boolean = Settings.getBoolean(KEY_VOLUME_PAGE, DEFAULT_VOLUME_PAGE)

    @JvmStatic
    fun putVolumePage(value: Boolean) = Settings.putBoolean(KEY_VOLUME_PAGE, value)

    // --- Reverse Volume Page (not hot-path, checked once at reader init) ---
    const val KEY_REVERSE_VOLUME_PAGE = "reverse_volume_page"
    private const val DEFAULT_REVERSE_VOLUME_PAGE = false

    @JvmStatic
    fun getReverseVolumePage(): Boolean = Settings.getBoolean(KEY_REVERSE_VOLUME_PAGE, DEFAULT_REVERSE_VOLUME_PAGE)

    @JvmStatic
    fun putReverseVolumePage(value: Boolean) = Settings.putBoolean(KEY_REVERSE_VOLUME_PAGE, value)

    // --- Reading Fullscreen (hot-path, cached) ---
    const val KEY_READING_FULLSCREEN = "reading_fullscreen"
    private const val DEFAULT_READING_FULLSCREEN = true

    @Volatile
    private var cachedReadingFullscreen: Boolean = DEFAULT_READING_FULLSCREEN

    @JvmStatic
    fun getReadingFullscreen(): Boolean = cachedReadingFullscreen

    @JvmStatic
    fun putReadingFullscreen(value: Boolean) {
        Settings.putBoolean(KEY_READING_FULLSCREEN, value)
        cachedReadingFullscreen = value
    }

    // --- Custom Screen Lightness (hot-path, cached) ---
    const val KEY_CUSTOM_SCREEN_LIGHTNESS = "custom_screen_lightness"
    private const val DEFAULT_CUSTOM_SCREEN_LIGHTNESS = false

    @Volatile
    private var cachedCustomScreenLightness: Boolean = DEFAULT_CUSTOM_SCREEN_LIGHTNESS

    @JvmStatic
    fun getCustomScreenLightness(): Boolean = cachedCustomScreenLightness

    @JvmStatic
    fun putCustomScreenLightness(value: Boolean) {
        Settings.putBoolean(KEY_CUSTOM_SCREEN_LIGHTNESS, value)
        cachedCustomScreenLightness = value
    }

    // --- Screen Lightness (hot-path, cached) ---
    const val KEY_SCREEN_LIGHTNESS = "screen_lightness"
    private const val DEFAULT_SCREEN_LIGHTNESS = 50

    @Volatile
    private var cachedScreenLightness: Int = DEFAULT_SCREEN_LIGHTNESS

    @JvmStatic
    fun getScreenLightness(): Int = cachedScreenLightness

    @JvmStatic
    fun putScreenLightness(value: Int) {
        Settings.putInt(KEY_SCREEN_LIGHTNESS, value)
        cachedScreenLightness = value
    }

    // --- Read Cache Size (not hot-path, checked once) ---
    const val KEY_READ_CACHE_SIZE = "read_cache_size"
    const val DEFAULT_READ_CACHE_SIZE = 160

    @JvmStatic
    fun getReadCacheSize(): Int = Settings.getIntFromStr(KEY_READ_CACHE_SIZE, DEFAULT_READ_CACHE_SIZE)

    // --- Show Read Progress (not hot-path, checked once) ---
    const val KEY_SHOW_READ_PROGRESS = "show_read_progress"
    private const val DEFAULT_SHOW_READ_PROGRESS = true

    @JvmStatic
    fun getShowReadProgress(): Boolean = Settings.getBoolean(KEY_SHOW_READ_PROGRESS, DEFAULT_SHOW_READ_PROGRESS)

    @JvmStatic
    fun setShowReadProgress(value: Boolean) = Settings.putBoolean(KEY_SHOW_READ_PROGRESS, value)

    // --- Listener for SP changes to keep volatile cache in sync ---

    private val preferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                KEY_READING_DIRECTION -> cachedReadingDirection = GalleryView.sanitizeLayoutMode(
                    Settings.getIntFromStr(KEY_READING_DIRECTION, DEFAULT_READING_DIRECTION)
                )
                KEY_PAGE_SCALING -> cachedPageScaling = GalleryView.sanitizeScaleMode(
                    Settings.getIntFromStr(KEY_PAGE_SCALING, DEFAULT_PAGE_SCALING)
                )
                KEY_START_POSITION -> cachedStartPosition = GalleryView.sanitizeStartPosition(
                    Settings.getIntFromStr(KEY_START_POSITION, DEFAULT_START_POSITION)
                )
                KEY_KEEP_SCREEN_ON -> cachedKeepScreenOn =
                    Settings.getBoolean(KEY_KEEP_SCREEN_ON, DEFAULT_KEEP_SCREEN_ON)
                KEY_SHOW_CLOCK -> cachedShowClock =
                    Settings.getBoolean(KEY_SHOW_CLOCK, DEFAULT_SHOW_CLOCK)
                KEY_SHOW_PROGRESS -> cachedShowProgress =
                    Settings.getBoolean(KEY_SHOW_PROGRESS, DEFAULT_SHOW_PROGRESS)
                KEY_SHOW_BATTERY -> cachedShowBattery =
                    Settings.getBoolean(KEY_SHOW_BATTERY, DEFAULT_SHOW_BATTERY)
                KEY_SHOW_PAGE_INTERVAL -> cachedShowPageInterval =
                    Settings.getBoolean(KEY_SHOW_PAGE_INTERVAL, DEFAULT_SHOW_PAGE_INTERVAL)
                KEY_READING_FULLSCREEN -> cachedReadingFullscreen =
                    Settings.getBoolean(KEY_READING_FULLSCREEN, DEFAULT_READING_FULLSCREEN)
                KEY_CUSTOM_SCREEN_LIGHTNESS -> cachedCustomScreenLightness =
                    Settings.getBoolean(KEY_CUSTOM_SCREEN_LIGHTNESS, DEFAULT_CUSTOM_SCREEN_LIGHTNESS)
                KEY_SCREEN_LIGHTNESS -> cachedScreenLightness =
                    Settings.getInt(KEY_SCREEN_LIGHTNESS, DEFAULT_SCREEN_LIGHTNESS)
            }
        }

    /**
     * Initialize cached volatile fields from SharedPreferences and register
     * a change listener to keep them in sync. Must be called after
     * [Settings.initialize] has set up the SharedPreferences instance.
     */
    @JvmStatic
    fun initialize() {
        val prefs = Settings.getPreferences()

        // Populate volatile caches from current SP values
        cachedReadingDirection = GalleryView.sanitizeLayoutMode(
            Settings.getIntFromStr(KEY_READING_DIRECTION, DEFAULT_READING_DIRECTION)
        )
        cachedPageScaling = GalleryView.sanitizeScaleMode(
            Settings.getIntFromStr(KEY_PAGE_SCALING, DEFAULT_PAGE_SCALING)
        )
        cachedStartPosition = GalleryView.sanitizeStartPosition(
            Settings.getIntFromStr(KEY_START_POSITION, DEFAULT_START_POSITION)
        )
        cachedKeepScreenOn = Settings.getBoolean(KEY_KEEP_SCREEN_ON, DEFAULT_KEEP_SCREEN_ON)
        cachedShowClock = Settings.getBoolean(KEY_SHOW_CLOCK, DEFAULT_SHOW_CLOCK)
        cachedShowProgress = Settings.getBoolean(KEY_SHOW_PROGRESS, DEFAULT_SHOW_PROGRESS)
        cachedShowBattery = Settings.getBoolean(KEY_SHOW_BATTERY, DEFAULT_SHOW_BATTERY)
        cachedShowPageInterval = Settings.getBoolean(KEY_SHOW_PAGE_INTERVAL, DEFAULT_SHOW_PAGE_INTERVAL)
        cachedReadingFullscreen = Settings.getBoolean(KEY_READING_FULLSCREEN, DEFAULT_READING_FULLSCREEN)
        cachedCustomScreenLightness = Settings.getBoolean(
            KEY_CUSTOM_SCREEN_LIGHTNESS, DEFAULT_CUSTOM_SCREEN_LIGHTNESS
        )
        cachedScreenLightness = Settings.getInt(KEY_SCREEN_LIGHTNESS, DEFAULT_SCREEN_LIGHTNESS)

        // Register listener (strong reference held by this object, never GC'd)
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }
}
