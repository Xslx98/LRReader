/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lanraragi.reader.gallery

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRHttpException
import kotlinx.coroutines.CancellationException

/**
 * Durable outbox for single-archive reading progress that failed to reach the
 * server (offline reading, a flaky network). Audit 2026-10-04 C20.
 *
 * Without it, a failed progress PUT was dropped: the phone resumed correctly
 * from its local save, but the server (and so every other device) kept the
 * old page. Tank sessions already keep a pending marker ([TankProgress]); this
 * is the single-archive sibling, flushed whenever the network comes back
 * instead of only on the next open.
 *
 * An entry is written only when a PUT fails and cleared when a PUT of a page
 * read at or after it succeeds, so a normal page turn costs no extra disk
 * write. Recency, not page equality, decides (audit 2026-10-06 C20): after a
 * blip that fails page 10 while 11-15 go through, the pending 10 is stale and
 * must not rewind the server when the network returns; a pending page read
 * after the success (an older PUT completing late) is kept.
 * Before pushing, [flush] compares the server's `lastreadtime` with the time
 * the page was read: if the server is clearly newer, another device read
 * since, and the stale local page is dropped instead of overwriting it.
 */
object ArchiveProgressOutbox {

    data class Entry(val baseUrl: String, val arcid: String, val page0: Int, val readAtSeconds: Long)

    /** Server-side facts [flush] needs about one archive. */
    data class ServerProgress(val lastReadTimeSeconds: Long)

    private const val TAG = "ArchiveProgressOutbox"
    private const val PREFS = "archive_progress_pending"
    private const val SEP = "@"

    @Volatile
    private var prefs: SharedPreferences? = null

    @VisibleForTesting
    internal var clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L }

    fun install(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    @VisibleForTesting
    internal fun installForTesting(testPrefs: SharedPreferences?) {
        prefs = testPrefs
    }

    /** A PUT of [page0] (read at [readAtSeconds]) for [arcid] on [baseUrl] failed: keep it for a later push. */
    fun markPending(baseUrl: String, arcid: String, page0: Int, readAtSeconds: Long = clockSeconds()) {
        if (page0 < 0) return
        prefs?.edit { putString(key(baseUrl, arcid), "$page0;$readAtSeconds") }
    }

    /**
     * A PUT of a page read at [readAtSeconds] succeeded: it supersedes a
     * pending entry read at or before then. A pending page read later stays.
     */
    fun markSynced(baseUrl: String, arcid: String, readAtSeconds: Long) {
        val p = prefs ?: return
        val key = key(baseUrl, arcid)
        val value = p.getString(key, null) ?: return
        val pending = parse(key, value)
        if (pending == null || pending.readAtSeconds <= readAtSeconds) p.edit { remove(key) }
    }

    /**
     * Run a progress [put] for [page0]: success drops a pending entry read at
     * or before this one, failure records one, and the failure is rethrown.
     * The read time is taken before the PUT, so a slow success cannot
     * outrank a page read while it was in flight.
     */
    suspend fun tracked(baseUrl: String, arcid: String, page0: Int, put: suspend () -> Unit) {
        val readAt = clockSeconds()
        try {
            put()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markPending(baseUrl, arcid, page0, readAt)
            throw e
        }
        markSynced(baseUrl, arcid, readAt)
    }

    fun entries(): List<Entry> {
        val p = prefs ?: return emptyList()
        return p.all.mapNotNull { (k, v) -> (v as? String)?.let { parse(k, it) } }
    }

    /**
     * Push the local page unless the server's last read is clearly newer than
     * the local one. Same skew allowance as [ReadingProgressReconciler]:
     * device and server clocks differ, and within the grace window both reads
     * count as the same session.
     */
    fun shouldPush(readAtSeconds: Long, serverLastReadSeconds: Long): Boolean =
        serverLastReadSeconds - readAtSeconds <= ReadingProgressReconciler.CLOCK_SKEW_GRACE_SECONDS

    /**
     * Try every pending entry once. A network failure keeps the entry for the
     * next attempt; an HTTP 4xx (archive gone, server refuses progress) drops it.
     *
     * @param fetch reads the archive's server progress
     * @param put sends the 1-indexed page
     */
    suspend fun flush(
        fetch: suspend (baseUrl: String, arcid: String) -> ServerProgress,
        put: suspend (baseUrl: String, arcid: String, page1: Int) -> Unit,
    ) {
        for (entry in entries()) {
            try {
                val server = fetch(entry.baseUrl, entry.arcid)
                val serverTs = ReadingProgressReconciler.normalizeEpochSeconds(server.lastReadTimeSeconds)
                if (shouldPush(entry.readAtSeconds, serverTs)) {
                    put(entry.baseUrl, entry.arcid, entry.page0 + 1)
                }
                removeIfUnchanged(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LRRHttpException) {
                if (e.code in 400..499) removeIfUnchanged(entry)
            } catch (e: Exception) {
                Log.e(TAG, "Progress outbox push failed; kept for the next attempt", e)
            }
        }
    }

    /** [flush] against the real servers. The auth interceptor picks each host's key. */
    suspend fun flushToServers() {
        if (entries().isEmpty()) return
        val client = ServiceRegistry.networkModule.okHttpClient
        flush(
            fetch = { baseUrl, arcid ->
                ServerProgress(LRRArchiveApi.getArchiveMetadata(client, baseUrl, arcid).lastreadtime)
            },
            put = { baseUrl, arcid, page1 -> LRRArchiveApi.updateProgress(client, baseUrl, arcid, page1) },
        )
    }

    /** Drops every pending page for [baseUrl] (its profile was deleted, audit SEC-16). */
    fun dropServer(baseUrl: String) {
        val p = prefs ?: return
        val keys = entries().filter { it.baseUrl == baseUrl }.map { key(it.baseUrl, it.arcid) }
        if (keys.isNotEmpty()) p.edit { keys.forEach { remove(it) } }
    }

    /** A live session may have queued a newer page meanwhile: keep that one. */
    private fun removeIfUnchanged(entry: Entry) {
        val p = prefs ?: return
        val key = key(entry.baseUrl, entry.arcid)
        val current = p.getString(key, null)?.let { parse(key, it) }
        if (current == entry) p.edit { remove(key) }
    }

    private fun key(baseUrl: String, arcid: String) = arcid + SEP + baseUrl

    private fun parse(key: String, value: String): Entry? {
        val at = key.indexOf(SEP)
        if (at <= 0) return null
        val parts = value.split(';')
        val page0 = parts.getOrNull(0)?.toIntOrNull()
        val readAt = parts.getOrNull(1)?.toLongOrNull()
        return if (page0 != null && readAt != null) {
            Entry(key.substring(at + 1), key.substring(0, at), page0, readAt)
        } else {
            null
        }
    }
}
