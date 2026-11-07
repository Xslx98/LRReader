package com.lanraragi.reader.server

import android.util.Log
import com.lanraragi.reader.BuildConfig
import com.lanraragi.reader.client.api.LRRServerApi
import kotlinx.coroutines.CancellationException

private const val TAG = "ServerExcludedNamespaces"

/**
 * Cached `/api/info` exclusions for [conn], or one best-effort fetch when none
 * were ever observed (populates [ServerCapabilityCache] and its store for next
 * time). Any failure (offline, old server) yields the empty set.
 */
suspend fun excludedNamespaces(conn: ServerConnection): Set<String> {
    val baseUrl = conn.baseUrl
    if (baseUrl.isEmpty()) return emptySet()
    ServerCapabilityCache.excludedNamespaces(baseUrl)?.let { return it }
    return try {
        LRRServerApi.getServerInfo(conn.client, baseUrl)
        ServerCapabilityCache.excludedNamespaces(baseUrl) ?: emptySet()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Guarded so R8 drops the whole call: the message template keeps
        // the line alive in release otherwise (docs/log-strip-cleanup.md).
        if (BuildConfig.DEBUG) Log.w(TAG, "excluded_namespaces unavailable, using built-in set: ${e.message}")
        emptySet()
    }
}
