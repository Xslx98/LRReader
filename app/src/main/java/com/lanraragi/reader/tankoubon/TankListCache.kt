package com.lanraragi.reader.tankoubon

import com.lanraragi.reader.client.api.LRRTankoubonApi
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived per-server copy of the whole tankoubon list. The detail page
 * only needs the names of the (usually one or two) tanks containing its
 * archive, but the list endpoint is the only source of names: without this
 * every detail open paged through the entire list.
 *
 * Writers that change tanks ([invalidate]) and full list loads ([put])
 * keep it honest; otherwise entries expire after [TTL_MS].
 */
object TankListCache {

    const val TTL_MS = 2 * 60 * 1000L
    private const val MAX_PAGES = 100

    private data class Entry(val loadedAt: Long, val tanks: List<LRRTankoubonApi.Tankoubon>)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** The cached list for [baseUrl], or a fresh fetch (pages are 0-based). */
    suspend fun get(
        client: OkHttpClient,
        baseUrl: String,
        now: Long = System.currentTimeMillis(),
    ): List<LRRTankoubonApi.Tankoubon> {
        entries[baseUrl]?.let { if (now - it.loadedAt < TTL_MS) return it.tanks }
        val all = mutableListOf<LRRTankoubonApi.Tankoubon>()
        var page = 0
        while (page < MAX_PAGES) {
            val r = LRRTankoubonApi.getTankoubons(client, baseUrl, page)
            all.addAll(r.result)
            if (r.result.isEmpty() || all.size >= r.total) break
            page++
        }
        put(baseUrl, all, now)
        return all
    }

    /** Record a complete list just loaded elsewhere. */
    fun put(baseUrl: String, tanks: List<LRRTankoubonApi.Tankoubon>, now: Long = System.currentTimeMillis()) {
        entries[baseUrl] = Entry(now, tanks.toList())
    }

    /** Drop [baseUrl]'s copy after a tank was created, changed or deleted. */
    fun invalidate(baseUrl: String) {
        entries.remove(baseUrl)
    }
}
