package com.hippo.ehviewer.settings

import com.hippo.ehviewer.Settings

/**
 * Guide/tip-related settings: first-time usage tips and gallery guide flags.
 */
object GuideSettings {

    // --- Quick Search Tip ---
    private const val KEY_QUICK_SEARCH_TIP = "quick_search_tip"
    private const val DEFAULT_QUICK_SEARCH_TIP = true

    @JvmStatic
    fun getQuickSearchTip(): Boolean = Settings.getBoolean(KEY_QUICK_SEARCH_TIP, DEFAULT_QUICK_SEARCH_TIP)

    @JvmStatic
    fun putQuickSearchTip(value: Boolean) = Settings.putBoolean(KEY_QUICK_SEARCH_TIP, value)

    // --- Guide Gallery ---
    private const val KEY_GUIDE_GALLERY = "guide_gallery"
    private const val DEFAULT_GUIDE_GALLERY = true

    @JvmStatic
    fun getGuideGallery(): Boolean = Settings.getBoolean(KEY_GUIDE_GALLERY, DEFAULT_GUIDE_GALLERY)

    @JvmStatic
    fun putGuideGallery(value: Boolean) = Settings.putBoolean(KEY_GUIDE_GALLERY, value)
}
