package com.lanraragi.reader.util

import android.content.Context
import android.os.storage.StorageManager

/**
 * Disk-cache budgets bounded by the system cache quota (audit C35).
 *
 * The reader page cache (500 MB), the thumbnail cache (80-320 MB) and the
 * HTTP cache (200 MB) used fixed sizes adding up to ~1 GB on every device.
 * An app above [StorageManager.getCacheQuotaBytes] is the first whose cache
 * the system clears when space runs low, so when [QUOTA_SHARE] of the quota
 * is smaller than the defaults, every budget shrinks by the same factor.
 * A budget never grows past its default and never drops below its floor.
 */
object CacheBudget {

    private const val MB = 1024L * 1024L

    const val READER_PAGES_DEFAULT = 500 * MB
    const val HTTP_DEFAULT = 200 * MB

    /** Largest thumbnail tier (see `ClientModule.tieredDiskCacheSize`). */
    private const val THUMBS_MAX = 320 * MB

    private const val READER_PAGES_FLOOR = 64 * MB
    private const val THUMBS_FLOOR = 32 * MB
    private const val HTTP_FLOOR = 16 * MB

    private const val DEFAULT_TOTAL = READER_PAGES_DEFAULT + THUMBS_MAX + HTTP_DEFAULT

    /** Share of the quota the three caches may use together; the rest is left for small caches. */
    private const val QUOTA_SHARE = 0.9

    @Volatile
    private var quotaBytes: Long = -1L

    fun readerPages(context: Context): Long = scale(READER_PAGES_DEFAULT, READER_PAGES_FLOOR, quota(context))

    fun http(context: Context): Long = scale(HTTP_DEFAULT, HTTP_FLOOR, quota(context))

    fun thumbs(context: Context, tierBytes: Long): Long = scale(tierBytes, THUMBS_FLOOR, quota(context))

    /** [defaultBytes] scaled to fit the quota; unknown quota (<= 0) keeps the default. */
    internal fun scale(defaultBytes: Long, floorBytes: Long, quotaBytes: Long): Long {
        if (quotaBytes <= 0) return defaultBytes
        val allowed = quotaBytes * QUOTA_SHARE
        if (allowed >= DEFAULT_TOTAL) return defaultBytes
        val scaled = (defaultBytes * (allowed / DEFAULT_TOTAL)).toLong()
        return scaled.coerceIn(minOf(floorBytes, defaultBytes), defaultBytes)
    }

    /** Read once per process (one binder call); 0 when the platform cannot tell. */
    private fun quota(context: Context): Long {
        val cached = quotaBytes
        if (cached >= 0) return cached
        val read = try {
            val sm = context.getSystemService(StorageManager::class.java)
            sm?.getCacheQuotaBytes(sm.getUuidForPath(context.cacheDir)) ?: 0L
        } catch (e: Exception) {
            // IOException for an unknown volume; vendor ROMs and test
            // runtimes throw others. Defaults are the safe answer.
            android.util.Log.e(TAG, "Cache quota unavailable", e)
            0L
        }
        quotaBytes = read
        return read
    }

    private const val TAG = "CacheBudget"
}
