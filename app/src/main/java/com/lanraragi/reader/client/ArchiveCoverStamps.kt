package com.lanraragi.reader.client

import com.lanraragi.reader.Settings
import com.lanraragi.reader.client.api.isTankoubonId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Per-archive cover cache-bust stamps. An archive's cover URL never changes,
 * and both the image cache (key `preview:large:{arcid}:{stamp}`, see
 * [LRRCacheKeyFactory.getThumbKey]) and the OkHttp cache (thumbnails are
 * fresh for an hour, query-string URLs are exempt) would keep serving the
 * old picture after `PUT /api/archives/{id}/thumbnail`. [bump] gives the
 * archive a new stamp; [bust] appends it to the URL. Persisted as a small
 * JSON map (`archive_cover_stamps`) so a restart does not resurrect the
 * old cached cover; stamp 0 (never changed) keeps today's key and URL.
 */
object ArchiveCoverStamps {

    private const val KEY = "archive_cover_stamps"
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var loaded: MutableMap<String, Long>? = null

    /** Test seam: replaces the persisted map (null = read from [Settings] on next access). */
    @Synchronized
    internal fun resetForTest(initial: Map<String, Long>? = emptyMap()) {
        loaded = initial?.toMutableMap()
    }

    /** Cover stamp for [arcid]; a `TANK_` id answers the process-wide [TankCoverCacheStamp]. */
    fun get(arcid: String): Long =
        if (isTankoubonId(arcid)) TankCoverCacheStamp.value else map()[arcid] ?: 0L

    @Synchronized
    fun bump(arcid: String) {
        val m = map()
        m[arcid] = maxOf(System.currentTimeMillis(), (m[arcid] ?: 0L) + 1)
        persist(m)
    }

    /**
     * [url] with `ts=<stamp>` appended when the cover may have changed: an
     * archive whose cover the app changed, or any tank row (the tank stamp
     * moves on every tank fetch and cover write).
     */
    fun bust(url: String, arcid: String): String {
        val stamp = get(arcid)
        if (stamp == 0L || url.isEmpty()) return url
        val sep = if ('?' in url) '&' else '?'
        return "$url${sep}ts=$stamp"
    }

    private fun map(): MutableMap<String, Long> {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            val raw = runCatching { Settings.getString(KEY, null) }.getOrNull()
            val parsed = raw?.takeIf { it.isNotBlank() }
                ?.let { runCatching { json.decodeFromString<Map<String, Long>>(it) }.getOrNull() }
            return (parsed?.toMutableMap() ?: mutableMapOf()).also { loaded = it }
        }
    }

    private fun persist(m: Map<String, Long>) {
        runCatching { Settings.putString(KEY, json.encodeToString(m)) }
    }
}
