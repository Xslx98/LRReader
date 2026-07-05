package com.lanraragi.reader.client.api

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-level per-server capability cache for Tankoubon support
 * (LANraragi 0.9.8+ routes: /full, tank thumbnail/progress, archive reverse
 * lookup). Keyed by baseUrl like [ServerCapabilityCache] — one profile maps
 * to one baseUrl, so this honours per-profile isolation without a Room read.
 *
 * Only a definite 404 marks UNSUPPORTED (the pre-0.9.8 server has no such
 * route). Network failures / 5xx / reverse-proxy noise never mark — a flaky
 * LAN must not permanently hide the feature (cache is process-lifetime only).
 */
object TankoubonSupportGate {

    enum class Support { UNKNOWN, SUPPORTED, UNSUPPORTED }

    private val states = ConcurrentHashMap<String, Support>()

    private fun key(baseUrl: String) = baseUrl.trimEnd('/')

    @JvmStatic
    fun support(baseUrl: String): Support = states[key(baseUrl)] ?: Support.UNKNOWN

    @JvmStatic
    fun isUnsupported(baseUrl: String): Boolean = support(baseUrl) == Support.UNSUPPORTED

    @JvmStatic
    fun markSupported(baseUrl: String) {
        states[key(baseUrl)] = Support.SUPPORTED
    }

    /** Returns true when [e] proves the server lacks tankoubon routes (marked). */
    @JvmStatic
    fun markFrom(baseUrl: String, e: Exception): Boolean {
        if (e is LRRHttpException && e.code == 404) {
            states[key(baseUrl)] = Support.UNSUPPORTED
            return true
        }
        return false
    }

    @JvmStatic
    fun clear() {
        states.clear()
    }
}
