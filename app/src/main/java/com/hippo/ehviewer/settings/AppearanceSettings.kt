package com.hippo.ehviewer.settings

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.DimenRes
import androidx.core.content.edit
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.LRRUtils
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene
import java.io.File
import java.util.Locale

/**
 * Appearance-related settings extracted from Settings.java.
 * Covers theme, list mode, thumbnail size, title display, tag translations, etc.
 */
object AppearanceSettings {

    // --- Theme ---
    const val KEY_THEME = "theme"
    const val THEME_LIGHT = 0
    const val THEME_DARK = 1
    const val THEME_BLACK = 2
    private const val DEFAULT_THEME = THEME_LIGHT

    @JvmStatic
    fun getTheme(): Int = Settings.getIntFromStr(KEY_THEME, DEFAULT_THEME)

    @JvmStatic
    fun putTheme(theme: Int) = Settings.putIntToStr(KEY_THEME, theme)

    @JvmStatic
    fun getDarkModeStatus(context: Context): Boolean {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    // --- Theme Auto Switch ---
    const val KEY_THEME_AUTO_SWITCH = "theme_auto_switch"

    @JvmStatic
    fun isThemeAutoSwitchAvailable(): Boolean = Settings.getBoolean(KEY_THEME_AUTO_SWITCH, false)

    // --- Dark theme variant ---
    // Which dark theme (THEME_DARK or THEME_BLACK) auto-switch restores when
    // the system turns dark. Without it, every dark transition hardcoded
    // THEME_DARK and a chosen BLACK was permanently lost after one
    // light/dark round trip.
    const val KEY_DARK_THEME_VARIANT = "dark_theme_variant"

    @JvmStatic
    fun getDarkThemeVariant(): Int = Settings.getIntFromStr(KEY_DARK_THEME_VARIANT, THEME_DARK)

    @JvmStatic
    fun putDarkThemeVariant(theme: Int) = Settings.putIntToStr(KEY_DARK_THEME_VARIANT, theme)

    /**
     * Mirror the system dark mode into the app theme, preserving the user's
     * chosen dark variant (DARK vs BLACK) across light/dark round trips.
     * Shared by cold start ([com.hippo.ehviewer.Settings.initialize]), the
     * runtime uiMode change (EhActivity.onConfigurationChanged), and enabling
     * the auto-switch preference — previously three diverging copies, each of
     * which hardcoded THEME_DARK on the dark transition.
     *
     * @return true when the persisted theme changed (caller should recreate).
     */
    @JvmStatic
    fun syncThemeWithSystem(isSystemDark: Boolean): Boolean {
        val theme = getTheme()
        val isAppDark = theme != THEME_LIGHT
        if (isSystemDark == isAppDark) {
            // Already on the right side. Capture a manually-chosen dark
            // variant so the next dark transition restores it.
            if (isAppDark) putDarkThemeVariant(theme)
            return false
        }
        if (isSystemDark) {
            putTheme(getDarkThemeVariant())
        } else {
            // theme is a dark variant here — remember it before going light.
            putDarkThemeVariant(theme)
            putTheme(THEME_LIGHT)
        }
        return true
    }

    // --- Gallery Site ---
    const val KEY_GALLERY_SITE = "gallery_site"
    private const val DEFAULT_GALLERY_SITE = 0

    @JvmStatic
    fun getGallerySite(): Int = Settings.getIntFromStr(KEY_GALLERY_SITE, DEFAULT_GALLERY_SITE)

    @JvmStatic
    fun putGallerySite(value: Int) = Settings.putIntToStr(KEY_GALLERY_SITE, value)

    // --- Launch Page ---
    private const val KEY_LAUNCH_PAGE = "launch_page"
    private const val DEFAULT_LAUNCH_PAGE = 0

    @JvmStatic
    fun getLaunchPageGalleryListSceneAction(): String {
        return when (Settings.getIntFromStr(KEY_LAUNCH_PAGE, DEFAULT_LAUNCH_PAGE)) {
            1 -> GalleryListScene.ACTION_SUBSCRIPTION
            2 -> GalleryListScene.ACTION_WHATS_HOT
            else -> GalleryListScene.ACTION_HOMEPAGE
        }
    }

    // --- List Mode ---
    const val KEY_LIST_MODE = "list_mode"
    private const val DEFAULT_LIST_MODE = 0

    @JvmStatic
    fun getListMode(): Int = Settings.getIntFromStr(KEY_LIST_MODE, DEFAULT_LIST_MODE)

    // --- Detail Size ---
    const val KEY_DETAIL_SIZE = "detail_size"
    private const val DEFAULT_DETAIL_SIZE = 0

    @JvmStatic
    fun getDetailSize(): Int = Settings.getIntFromStr(KEY_DETAIL_SIZE, DEFAULT_DETAIL_SIZE)

    @JvmStatic
    @DimenRes
    fun getDetailSizeResId(): Int = when (getDetailSize()) {
        1 -> R.dimen.gallery_list_column_width_short
        else -> R.dimen.gallery_list_column_width_long
    }

    // --- Thumb Size ---
    const val KEY_THUMB_SIZE = "thumb_size"
    private const val DEFAULT_THUMB_SIZE = 1

    @JvmStatic
    fun getThumbSize(): Int = Settings.getIntFromStr(KEY_THUMB_SIZE, DEFAULT_THUMB_SIZE)

    @JvmStatic
    @DimenRes
    fun getThumbSizeResId(): Int = when (getThumbSize()) {
        0 -> R.dimen.gallery_grid_column_width_large
        2 -> R.dimen.gallery_grid_column_width_small
        else -> R.dimen.gallery_grid_column_width_middle
    }

    // --- Detail Page Thumb Size ---
    // Picks the target tile width for the per-page thumbnail grid on
    // the archive detail page. Separate from KEY_THUMB_SIZE (which
    // sizes the gallery-list grid) so the user can run a dense list
    // with chunky preview tiles or vice versa.
    const val KEY_DETAIL_PAGE_THUMB_SIZE = "detail_page_thumb_size"
    private const val DEFAULT_DETAIL_PAGE_THUMB_SIZE = 1

    @JvmStatic
    fun getDetailPageThumbSize(): Int =
        Settings.getIntFromStr(KEY_DETAIL_PAGE_THUMB_SIZE, DEFAULT_DETAIL_PAGE_THUMB_SIZE)

    @JvmStatic
    @DimenRes
    fun getDetailPageThumbSizeResId(): Int = when (getDetailPageThumbSize()) {
        0 -> R.dimen.page_thumb_target_width_large
        2 -> R.dimen.page_thumb_target_width_small
        else -> R.dimen.page_thumb_target_width_middle
    }

    // --- Show Gallery Pages ---
    private const val KEY_SHOW_GALLERY_PAGES = "show_gallery_pages"

    @JvmStatic
    fun getShowGalleryPages(): Boolean = Settings.getBoolean(KEY_SHOW_GALLERY_PAGES, false)

    // --- Tankoubon grouping in browse/search (LANraragi groupby_tanks) ---
    const val KEY_GROUP_TANKS = "search_group_tanks"
    private const val DEFAULT_GROUP_TANKS = true

    @JvmStatic
    fun getGroupTanks(): Boolean = Settings.getBoolean(KEY_GROUP_TANKS, DEFAULT_GROUP_TANKS)

    @JvmStatic
    fun putGroupTanks(value: Boolean) = Settings.putBoolean(KEY_GROUP_TANKS, value)

    // --- Hide completed archives in browse/search (LANraragi hidecompleted, 0.9.8+) ---
    const val KEY_HIDE_COMPLETED = "search_hide_completed"
    private const val DEFAULT_HIDE_COMPLETED = false

    @JvmStatic
    fun getHideCompleted(): Boolean = Settings.getBoolean(KEY_HIDE_COMPLETED, DEFAULT_HIDE_COMPLETED)

    @JvmStatic
    fun putHideCompleted(value: Boolean) = Settings.putBoolean(KEY_HIDE_COMPLETED, value)

    // --- Show Tag Translations ---
    const val KEY_SHOW_TAG_TRANSLATIONS = "show_tag_translations"

    @JvmStatic
    fun getShowTagTranslations(): Boolean {
        if ("zh" != Locale.getDefault().language) return false
        return Settings.getBoolean(KEY_SHOW_TAG_TRANSLATIONS, true)
    }

    // --- Default Categories ---
    const val KEY_DEFAULT_CATEGORIES = "default_categories"
    @JvmField val DEFAULT_DEFAULT_CATEGORIES = LRRUtils.ALL_CATEGORY

    @JvmStatic
    fun getDefaultCategories(): Int = Settings.getInt(KEY_DEFAULT_CATEGORIES, DEFAULT_DEFAULT_CATEGORIES)

    @JvmStatic
    fun putDefaultCategories(value: Int) = Settings.putInt(KEY_DEFAULT_CATEGORIES, value)

    // --- Show Gallery Comment ---
    const val KEY_SHOW_GALLERY_COMMENT = "show_gallery_comment"

    @JvmStatic
    fun getShowGalleryComment(): Boolean = Settings.getBoolean(KEY_SHOW_GALLERY_COMMENT, true)

    @JvmStatic
    fun setShowGalleryComment(value: Boolean) = Settings.putBoolean(KEY_SHOW_GALLERY_COMMENT, value)

    // --- Show Gallery Rating ---
    const val KEY_SHOW_GALLERY_RATING = "show_gallery_rating"

    @JvmStatic
    fun getShowGalleryRating(): Boolean = Settings.getBoolean(KEY_SHOW_GALLERY_RATING, true)

    @JvmStatic
    fun setShowGalleryRating(value: Boolean) = Settings.putBoolean(KEY_SHOW_GALLERY_RATING, value)

    // --- App Language ---
    const val KEY_APP_LANGUAGE = "app_language"
    private const val DEFAULT_APP_LANGUAGE = "system"

    @JvmStatic
    fun getAppLanguage(): String = Settings.getString(KEY_APP_LANGUAGE, DEFAULT_APP_LANGUAGE) ?: DEFAULT_APP_LANGUAGE

    @JvmStatic
    fun putAppLanguage(value: String?) {
        // Persist synchronously (commit, not apply): a language change immediately
        // restarts the process via Process.killProcess, and a hard kill does not
        // flush QueuedWork — an async apply() could lose the write, leaving the
        // fresh process to read the old locale in attachBaseContext. Writes to the
        // default SharedPreferences, the exact store attachBaseContext reads.
        Settings.getPreferences().edit(commit = true) { putString(KEY_APP_LANGUAGE, value) }
    }

    /**
     * Resolve a persisted [KEY_APP_LANGUAGE] value into a [Locale].
     *
     * [DEFAULT_APP_LANGUAGE] ("system"), `null`, or an unparseable value all mean
     * "follow the device locale" and return the system configuration locale.
     * Otherwise the value is a hyphen-separated tag: `ja`, `zh-CN`, `de-DE-POSIX`.
     *
     * Pure function — it takes the already-read preference string, so it is safe
     * to call from `attachBaseContext` before [Settings] is initialized. Shared by
     * `EhApplication` and `EhActivity` so process-context and per-activity locale
     * resolution can never drift.
     */
    @JvmStatic
    fun resolveAppLocale(language: String?): Locale {
        if (language != null && language != DEFAULT_APP_LANGUAGE) {
            val split = language.split("-")
            val parsed = when (split.size) {
                1 -> Locale(split[0])
                2 -> Locale(split[0], split[1])
                3 -> Locale(split[0], split[1], split[2])
                else -> null
            }
            if (parsed != null) return parsed
        }
        return Resources.getSystem().configuration.locale
    }

    // --- History Info Size ---
    const val KEY_HISTORY_INFO_SIZE = "history_info_size"
    const val DEFAULT_HISTORY_INFO_SIZE = 100

    @JvmStatic
    fun getHistoryInfoSize(): Int {
        val size = Settings.getIntFromStr(KEY_HISTORY_INFO_SIZE, DEFAULT_HISTORY_INFO_SIZE)
        if (size < DEFAULT_HISTORY_INFO_SIZE) {
            setHistoryInfoSize(DEFAULT_HISTORY_INFO_SIZE)
            return DEFAULT_HISTORY_INFO_SIZE
        }
        return size
    }

    @JvmStatic
    fun setHistoryInfoSize(value: Int) = Settings.putIntToStr(KEY_HISTORY_INFO_SIZE, value)

    // --- Display Name ---
    private const val KEY_DISPLAY_NAME = "display_name"

    @JvmStatic
    fun getDisplayName(): String? = Settings.getString(KEY_DISPLAY_NAME, null)

    @JvmStatic
    fun putDisplayName(value: String?) = Settings.putString(KEY_DISPLAY_NAME, value)

    // --- Avatar URL ---
    private const val KEY_AVATAR = "avatar"

    @JvmStatic
    fun getAvatar(): String? = Settings.getString(KEY_AVATAR, null)

    @JvmStatic
    fun putAvatar(value: String?) = Settings.putString(KEY_AVATAR, value)

    // --- User Image Paths (background + avatar overlay) ---
    @JvmField
    val USER_BACKGROUND_IMAGE = "background_image_path"
    @JvmField
    val USER_AVATAR_IMAGE = "avatar_image_path"

    @JvmStatic
    fun getUserImageFile(key: String): File? {
        val path = Settings.getString(key, "") ?: ""
        if (path.isEmpty()) {
            return null
        }
        val file = File(path)
        return if (file.exists()) file else null
    }

    @JvmStatic
    fun saveFilePath(key: String, path: String?) {
        Settings.putString(key, path)
    }
}
