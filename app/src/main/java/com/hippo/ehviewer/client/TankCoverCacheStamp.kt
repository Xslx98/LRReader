package com.hippo.ehviewer.client

/**
 * Process-wide cache-bust stamp for tankoubon cover images.
 *
 * The image pipeline caches by KEY (and its disk cache survives process
 * restarts) while a tank's cover URL never changes — so a cover
 * regenerated server-side (web-client set-cover, member reorder,
 * first-member removal, or any other client) stays shadowed by the stale
 * cached image forever, the "no thumbnail" placeholder included.
 *
 * Every successful fetch of tank data (list / detail) and every in-app
 * cover write bumps this stamp; cover binds fold [value] into both the
 * cache key and the URL (`ts` query param). Net effect: opening or
 * refreshing a tank screen revalidates its covers against the server,
 * while failed/offline loads leave the stamp untouched and keep serving
 * the cached images.
 */
object TankCoverCacheStamp {

    @Volatile
    var value: Long = 0L
        private set

    /**
     * Call after any successful tank-data fetch or cover write. Strictly
     * increasing even within one millisecond, so a bump is always
     * observable as a key change.
     */
    @Synchronized
    fun bump() {
        value = maxOf(System.currentTimeMillis(), value + 1)
    }
}
