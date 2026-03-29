package com.hippo.ehviewer.settings

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.DimenRes
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene
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

    // --- Nav Bar Theme ---
    const val KEY_APPLY_NAV_BAR_THEME_COLOR = "apply_nav_bar_theme_color"

    @JvmStatic
    fun getApplyNavBarThemeColor(): Boolean = Settings.getBoolean(KEY_APPLY_NAV_BAR_THEME_COLOR, false)

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

    // --- Show JPN Title ---
    private const val KEY_SHOW_JPN_TITLE = "show_jpn_title"

    @JvmStatic
    fun getShowJpnTitle(): Boolean = Settings.getBoolean(KEY_SHOW_JPN_TITLE, false)

    // --- Show Gallery Pages ---
    private const val KEY_SHOW_GALLERY_PAGES = "show_gallery_pages"

    @JvmStatic
    fun getShowGalleryPages(): Boolean = Settings.getBoolean(KEY_SHOW_GALLERY_PAGES, false)

    // --- Show Tag Translations ---
    const val KEY_SHOW_TAG_TRANSLATIONS = "show_tag_translations"

    @JvmStatic
    fun getShowTagTranslations(): Boolean {
        if ("zh" != Locale.getDefault().language) return false
        return Settings.getBoolean(KEY_SHOW_TAG_TRANSLATIONS, true)
    }

    // --- Default Categories ---
    const val KEY_DEFAULT_CATEGORIES = "default_categories"
    @JvmField val DEFAULT_DEFAULT_CATEGORIES = EhUtils.ALL_CATEGORY

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
    fun putAppLanguage(value: String?) = Settings.putString(KEY_APP_LANGUAGE, value)

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
}
