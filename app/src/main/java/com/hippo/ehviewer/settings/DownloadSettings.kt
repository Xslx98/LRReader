package com.hippo.ehviewer.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.annotation.Nullable
import androidx.core.content.edit
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.ui.CommonOperations
import com.hippo.unifile.UniFile
import com.hippo.util.ExceptionUtils

/**
 * Download-related settings extracted from Settings.java.
 * Covers download location, labels, preloading, ordering, pagination, delay, timeout,
 * archiver download cache, and delete-with-files preference.
 */
object DownloadSettings {

    private lateinit var sArchiverPre: SharedPreferences

    /** Must be called from [Settings.initialize] after context is available. */
    @JvmStatic
    fun initialize(context: Context) {
        sArchiverPre = context.getSharedPreferences("archiver_cache", Context.MODE_PRIVATE)
    }

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
        // Only file:// locations are honored. The download worker writes pages with
        // java.io.File and cannot write into a SAF tree (content://); a stored content://
        // location used to silently degrade to app-private storage — invisible files that
        // "delete with files" could never reclaim. Fall back to the default app file://
        // directory for any non-file (or unresolved) location.
        if (dir == null || dir.uri.scheme != "file") {
            return UniFile.fromFile(AppConfig.getDefaultDownloadDir())
        }
        return dir
    }

    /**
     * Snapshot of the current download root as a stable URI string.
     * Captured by the download write path at archive-add time so the
     * row's `DOWNLOAD_ROOT_URI` survives a later setting change. Returns
     * null when no location is configured (extremely rare — fresh
     * installs have a default fallback dir).
     */
    @JvmStatic
    fun getCurrentDownloadRootUri(): String? =
        getDownloadLocation()?.uri?.toString()

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

    // --- Concurrent Downloads (1-3) ---
    const val KEY_CONCURRENT_DOWNLOADS = "concurrent_downloads"
    private const val DEFAULT_CONCURRENT_DOWNLOADS = 1

    @JvmStatic
    fun getConcurrentDownloads(): Int =
        Settings.getIntFromStr(KEY_CONCURRENT_DOWNLOADS, DEFAULT_CONCURRENT_DOWNLOADS)
            .coerceIn(1, 3)

    @JvmStatic
    fun setConcurrentDownloads(value: Int) =
        Settings.putIntToStr(KEY_CONCURRENT_DOWNLOADS, value.coerceIn(1, 3))

    // --- Network-wait timeout (minutes; 0 = never time out) ---
    // How long a download keeps pausing/waiting for the network to return
    // before giving up and being marked FAILED. Stored as a string (ListPreference)
    // and parsed via getIntFromStr, matching the other list-backed settings.
    private const val KEY_NETWORK_RESUME_TIMEOUT = "network_resume_timeout"
    private const val DEFAULT_NETWORK_RESUME_TIMEOUT = 5

    @JvmStatic
    fun getNetworkResumeTimeoutMinutes(): Int =
        Settings.getIntFromStr(KEY_NETWORK_RESUME_TIMEOUT, DEFAULT_NETWORK_RESUME_TIMEOUT)

    // --- Remove Image Files (delete-with-files checkbox default) ---
    private const val KEY_REMOVE_IMAGE_FILES = "include_pic"
    private const val DEFAULT_REMOVE_IMAGE_FILES = true

    @JvmStatic
    fun getRemoveImageFiles(): Boolean = Settings.getBoolean(KEY_REMOVE_IMAGE_FILES, DEFAULT_REMOVE_IMAGE_FILES)

    @JvmStatic
    fun putRemoveImageFiles(value: Boolean) = Settings.putBoolean(KEY_REMOVE_IMAGE_FILES, value)

    // --- Archiver Download Cache ---
    // Uses a separate SharedPreferences ("archiver_cache") for system DownloadManager mappings.

    @JvmStatic
    fun getArchiverDownloadId(arcid: String): Long {
        return sArchiverPre.getLong("${arcid}DId", -1L)
    }

    @JvmStatic
    fun putArchiverDownloadId(arcid: String, downloadId: Long) {
        sArchiverPre.edit { putLong("${arcid}DId", downloadId) }
    }

    @JvmStatic
    fun deleteArchiverDownloadId(arcid: String) {
        sArchiverPre.edit { remove("${arcid}DId") }
    }
}
