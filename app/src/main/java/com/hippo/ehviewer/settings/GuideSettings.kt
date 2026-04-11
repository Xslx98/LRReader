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

    // --- Guide Quick Search ---
    private const val KEY_GUIDE_QUICK_SEARCH = "guide_quick_search"
    private const val DEFAULT_GUIDE_QUICK_SEARCH = true

    @JvmStatic
    fun getGuideQuickSearch(): Boolean = Settings.getBoolean(KEY_GUIDE_QUICK_SEARCH, DEFAULT_GUIDE_QUICK_SEARCH)

    @JvmStatic
    fun putGuideQuickSearch(value: Boolean) = Settings.putBoolean(KEY_GUIDE_QUICK_SEARCH, value)

    // --- Guide Collections ---
    private const val KEY_GUIDE_COLLECTIONS = "guide_collections"
    private const val DEFAULT_GUIDE_COLLECTIONS = true

    @JvmStatic
    fun getGuideCollections(): Boolean = Settings.getBoolean(KEY_GUIDE_COLLECTIONS, DEFAULT_GUIDE_COLLECTIONS)

    @JvmStatic
    fun putGuideCollections(value: Boolean) = Settings.putBoolean(KEY_GUIDE_COLLECTIONS, value)

    // --- Guide Download Thumb ---
    private const val KEY_GUIDE_DOWNLOAD_THUMB = "guide_download_thumb"
    private const val DEFAULT_GUIDE_DOWNLOAD_THUMB = true

    @JvmStatic
    fun getGuideDownloadThumb(): Boolean = Settings.getBoolean(KEY_GUIDE_DOWNLOAD_THUMB, DEFAULT_GUIDE_DOWNLOAD_THUMB)

    @JvmStatic
    fun putGuideDownloadThumb(value: Boolean) = Settings.putBoolean(KEY_GUIDE_DOWNLOAD_THUMB, value)

    // --- Guide Download Labels ---
    private const val KEY_GUIDE_DOWNLOAD_LABELS = "guide_download_labels"
    private const val DEFAULT_GUIDE_DOWNLOAD_LABELS = true

    @JvmStatic
    fun getGuideDownloadLabels(): Boolean = Settings.getBoolean(KEY_GUIDE_DOWNLOAD_LABELS, DEFAULT_GUIDE_DOWNLOAD_LABELS)

    @JvmStatic
    fun putGuideDownloadLabels(value: Boolean) = Settings.putBoolean(KEY_GUIDE_DOWNLOAD_LABELS, value)

    // --- Guide Gallery ---
    private const val KEY_GUIDE_GALLERY = "guide_gallery"
    private const val DEFAULT_GUIDE_GALLERY = true

    @JvmStatic
    fun getGuideGallery(): Boolean = Settings.getBoolean(KEY_GUIDE_GALLERY, DEFAULT_GUIDE_GALLERY)

    @JvmStatic
    fun putGuideGallery(value: Boolean) = Settings.putBoolean(KEY_GUIDE_GALLERY, value)
}
