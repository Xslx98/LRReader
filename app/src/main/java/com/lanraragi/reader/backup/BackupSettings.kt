package com.lanraragi.reader.backup

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Which default-prefs settings travel in a backup (ruling R18: all non-sensitive
 * ones). An explicit whitelist, so a new setting is left out until someone
 * decides it belongs. Never included: the lock (`security`, `enable_fingerprint`),
 * the download location (`image_*`, meaningless on another device), update and
 * guide bookkeeping, and anything in the encrypted prefs (API keys).
 */
object BackupSettings {

    val KEYS: Set<String> = setOf(
        // Appearance and lists
        "theme", "theme_auto_switch", "dark_theme_variant", "launch_page", "list_mode", "detail_size",
        "thumb_size", "detail_page_thumb_size", "show_gallery_pages", "search_group_tanks",
        "search_hide_completed", "show_tag_translations", "default_categories", "show_gallery_comment",
        "show_gallery_rating", "app_language", "history_info_size",
        // Downloads (not the location)
        "media_scan", "recent_download_label", "has_default_download_label", "default_download_label",
        "download_delay", "download_order_asc", "download_list_pagination", "drag_download_gallery",
        "concurrent_downloads", "network_resume_timeout", "include_pic",
        // Network and privacy
        "proxy_type", "proxy_ip", "proxy_port", "cellular_network_warning", "save_crash_log",
        "delete_confirm_countdown", "enable_secure",
        // Reader
        "screen_rotation", "reading_direction", "page_scaling", "start_position", "start_transfer_time",
        "keep_screen_on", "reader_continuation", "reader_continuation_swipe", "reader_stamps",
        "gallery_show_clock", "gallery_show_progress", "gallery_show_battery", "gallery_show_page_interval",
        "volume_page", "reverse_volume_page", "reading_fullscreen", "custom_screen_lightness",
        "screen_lightness", "read_cache_size", "show_read_progress",
        // Updates (the user's choices, not the bookkeeping)
        "beta_update_channel", "auto_check_for_updates",
    )

    const val TYPE_BOOLEAN = "boolean"
    const val TYPE_INT = "int"
    const val TYPE_LONG = "long"
    const val TYPE_FLOAT = "float"
    const val TYPE_STRING = "string"
    val TYPES = setOf(TYPE_BOOLEAN, TYPE_INT, TYPE_LONG, TYPE_FLOAT, TYPE_STRING)

    /** The whitelisted values present in [prefs]; string sets and unknown types are skipped. */
    fun export(prefs: SharedPreferences): List<BackupSetting> =
        prefs.all.entries
            .filter { it.key in KEYS }
            .mapNotNull { (key, value) ->
                when (value) {
                    is Boolean -> BackupSetting(key, TYPE_BOOLEAN, value.toString())
                    is Int -> BackupSetting(key, TYPE_INT, value.toString())
                    is Long -> BackupSetting(key, TYPE_LONG, value.toString())
                    is Float -> BackupSetting(key, TYPE_FLOAT, value.toString())
                    is String -> BackupSetting(key, TYPE_STRING, value)
                    else -> null
                }
            }
            .sortedBy { it.key }

    /**
     * Writes whitelisted [settings] into [prefs]. Keys outside the whitelist and
     * values that do not parse as their type are ignored.
     * @return how many settings were applied
     */
    fun apply(prefs: SharedPreferences, settings: List<BackupSetting>): Int {
        var applied = 0
        prefs.edit {
            for (s in settings) {
                if (s.key !in KEYS) continue
                val ok = when (s.type) {
                    TYPE_BOOLEAN -> s.value.toBooleanStrictOrNull()?.let { putBoolean(s.key, it) } != null
                    TYPE_INT -> s.value.toIntOrNull()?.let { putInt(s.key, it) } != null
                    TYPE_LONG -> s.value.toLongOrNull()?.let { putLong(s.key, it) } != null
                    TYPE_FLOAT -> s.value.toFloatOrNull()?.let { putFloat(s.key, it) } != null
                    TYPE_STRING -> putString(s.key, s.value) != null
                    else -> false
                }
                if (ok) applied++
            }
        }
        return applied
    }
}
