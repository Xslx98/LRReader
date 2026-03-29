package com.hippo.ehviewer.settings

import android.net.Uri
import androidx.annotation.Nullable
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.ui.CommonOperations
import com.hippo.unifile.UniFile
import com.hippo.util.ExceptionUtils

/**
 * Download-related settings extracted from Settings.java.
 * Covers download location, labels, preloading, ordering, pagination, delay, and timeout.
 */
object DownloadSettings {

    // --- Download Location (URI components) ---
    const val KEY_DOWNLOAD_SAVE_SCHEME = "image_scheme"
    const val KEY_DOWNLOAD_SAVE_AUTHORITY = "image_authority"
    const val KEY_DOWNLOAD_SAVE_PATH = "image_path"
    const val KEY_DOWNLOAD_SAVE_QUERY = "image_query"
    const val KEY_DOWNLOAD_SAVE_FRAGMENT = "image_fragment"

    @JvmStatic
    @Nullable
    fun getDownloadLocation(): UniFile? {
        var dir: UniFile? = null
        try {
            val builder = Uri.Builder()
            builder.scheme(Settings.getString(KEY_DOWNLOAD_SAVE_SCHEME, null))
            builder.encodedAuthority(Settings.getString(KEY_DOWNLOAD_SAVE_AUTHORITY, null))
            builder.encodedPath(Settings.getString(KEY_DOWNLOAD_SAVE_PATH, null))
            builder.encodedQuery(Settings.getString(KEY_DOWNLOAD_SAVE_QUERY, null))
            builder.encodedFragment(Settings.getString(KEY_DOWNLOAD_SAVE_FRAGMENT, null))
            dir = UniFile.fromUri(Settings.getContext(), builder.build())
        } catch (e: Throwable) {
            ExceptionUtils.throwIfFatal(e)
        }
        return dir ?: UniFile.fromFile(AppConfig.getDefaultDownloadDir())
    }

    @JvmStatic
    fun putDownloadLocation(location: UniFile) {
        val uri = location.uri
        Settings.putString(KEY_DOWNLOAD_SAVE_SCHEME, uri.scheme)
        Settings.putString(KEY_DOWNLOAD_SAVE_AUTHORITY, uri.encodedAuthority)
        Settings.putString(KEY_DOWNLOAD_SAVE_PATH, uri.encodedPath)
        Settings.putString(KEY_DOWNLOAD_SAVE_QUERY, uri.encodedQuery)
        Settings.putString(KEY_DOWNLOAD_SAVE_FRAGMENT, uri.encodedFragment)

        if (getMediaScan()) {
            CommonOperations.removeNoMediaFile(location)
        } else {
            CommonOperations.ensureNoMediaFile(location)
        }
    }

    // --- Media Scan ---
    const val KEY_MEDIA_SCAN = "media_scan"
    private const val DEFAULT_MEDIA_SCAN = false

    @JvmStatic
    fun getMediaScan(): Boolean = Settings.getBoolean(KEY_MEDIA_SCAN, DEFAULT_MEDIA_SCAN)

    // --- Recent Download Label ---
    private const val KEY_RECENT_DOWNLOAD_LABEL = "recent_download_label"

    @JvmStatic
    fun getRecentDownloadLabel(): String? = Settings.getString(KEY_RECENT_DOWNLOAD_LABEL, null)

    @JvmStatic
    fun putRecentDownloadLabel(value: String?) = Settings.putString(KEY_RECENT_DOWNLOAD_LABEL, value)

    // --- Has Default Download Label ---
    private const val KEY_HAS_DEFAULT_DOWNLOAD_LABEL = "has_default_download_label"

    @JvmStatic
    fun getHasDefaultDownloadLabel(): Boolean = Settings.getBoolean(KEY_HAS_DEFAULT_DOWNLOAD_LABEL, false)

    @JvmStatic
    fun putHasDefaultDownloadLabel(value: Boolean) = Settings.putBoolean(KEY_HAS_DEFAULT_DOWNLOAD_LABEL, value)

    // --- Default Download Label ---
    private const val KEY_DEFAULT_DOWNLOAD_LABEL = "default_download_label"

    @JvmStatic
    fun getDefaultDownloadLabel(): String? = Settings.getString(KEY_DEFAULT_DOWNLOAD_LABEL, null)

    @JvmStatic
    fun putDefaultDownloadLabel(value: String?) = Settings.putString(KEY_DEFAULT_DOWNLOAD_LABEL, value)

    // --- Preload Image ---
    private const val KEY_PRELOAD_IMAGE = "preload_image"
    private const val DEFAULT_PRELOAD_IMAGE = 5

    @JvmStatic
    fun getPreloadImage(): Int = Settings.getIntFromStr(KEY_PRELOAD_IMAGE, DEFAULT_PRELOAD_IMAGE)

    @JvmStatic
    fun putPreloadImage(value: Int) = Settings.putIntToStr(KEY_PRELOAD_IMAGE, value)

    // --- Download Delay ---
    private const val KEY_DOWNLOAD_DELAY = "download_delay"
    private const val DEFAULT_DOWNLOAD_DELAY = 0

    @JvmStatic
    fun getDownloadDelay(): Int = Settings.getIntFromStr(KEY_DOWNLOAD_DELAY, DEFAULT_DOWNLOAD_DELAY)

    @JvmStatic
    fun putDownloadDelay(value: Int) = Settings.putIntToStr(KEY_DOWNLOAD_DELAY, value)

    // --- Download Order ---
    const val KEY_DOWNLOAD_ORDER_ASC = "download_order_asc"

    @JvmStatic
    fun getDownloadOrder(): Boolean = Settings.getBoolean(KEY_DOWNLOAD_ORDER_ASC, true)

    @JvmStatic
    fun setDownloadOrder(value: Boolean) = Settings.putBoolean(KEY_DOWNLOAD_ORDER_ASC, value)

    // --- Download List Pagination ---
    const val KEY_DOWNLOAD_LIST_PAGINATION = "download_list_pagination"

    @JvmStatic
    fun getDownloadPagination(): Boolean = Settings.getBoolean(KEY_DOWNLOAD_LIST_PAGINATION, true)

    @JvmStatic
    fun setDownloadPagination(value: Boolean) = Settings.putBoolean(KEY_DOWNLOAD_LIST_PAGINATION, value)

    // --- Drag Download Gallery ---
    const val KEY_DRAG_DOWNLOAD_GALLERY = "drag_download_gallery"

    @JvmStatic
    fun getDragDownloadGallery(): Boolean = Settings.getBoolean(KEY_DRAG_DOWNLOAD_GALLERY, false)

    @JvmStatic
    fun setDragDownloadGallery(value: Boolean) = Settings.putBoolean(KEY_DRAG_DOWNLOAD_GALLERY, value)

    // --- Download Timeout ---
    const val KEY_DOWNLOAD_TIMEOUT = "download_timeout"
    const val DEFAULT_DOWNLOAD_TIMEOUT = 0

    @JvmStatic
    fun getDownloadTimeout(): Int = Math.max(Settings.getIntFromStr(KEY_DOWNLOAD_TIMEOUT, DEFAULT_DOWNLOAD_TIMEOUT), DEFAULT_DOWNLOAD_TIMEOUT)

    @JvmStatic
    fun setDownloadTimeout(value: Int) = Settings.putIntToStr(KEY_DOWNLOAD_TIMEOUT, value)
}
