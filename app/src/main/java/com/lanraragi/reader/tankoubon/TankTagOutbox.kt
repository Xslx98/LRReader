/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lanraragi.reader.tankoubon

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.client.api.LRRTankoubonApi
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Durable retry for the tank tag re-materialization that follows a member
 * archive's tag edit (spec 2026-09-22 §5.2, audit 2026-10-04 REL-24).
 *
 * The archive `PUT` lands first; then every tank containing the archive
 * recomputes its own tags from the member's OLD → NEW contribution. That
 * "before" string exists only at edit time — no later reconciler can
 * rebuild it — so a failed follow-up (tank list, `/full` snapshot or tank
 * `PUT`) is kept here and replayed at start and whenever the network comes
 * back. Replaying is idempotent: rule 3 applied twice gives the same string.
 *
 * One entry per (server, archive). A second edit before the retry landed
 * keeps the FIRST old string and the LATEST new one, which is the same
 * change as both edits in a row.
 */
object TankTagOutbox {

    data class Entry(val baseUrl: String, val arcid: String, val oldTags: String, val newTags: String)

    /** Lists the tank ids containing an archive. */
    fun interface TankLister {
        suspend fun tanksOf(baseUrl: String, arcid: String): List<String>
    }

    /** Re-materializes one tank's tags for a member edit. */
    fun interface TankSync {
        suspend fun sync(baseUrl: String, tankId: String, entry: Entry): TankTagSyncer.Outcome
    }

    @Serializable
    private data class Stored(val old: String, val new: String)

    private const val TAG = "TankTagOutbox"
    private const val PREFS = "tank_tag_pending"
    private const val SEP = "@"
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var prefs: SharedPreferences? = null

    fun install(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    @VisibleForTesting
    internal fun installForTesting(testPrefs: SharedPreferences?) {
        prefs = testPrefs
    }

    fun entries(): List<Entry> {
        val p = prefs ?: return emptyList()
        return p.all.mapNotNull { (k, v) -> (v as? String)?.let { parse(k, it) } }
    }

    /**
     * Apply a member edit ([oldTags] → [newTags]) to every tank containing
     * [arcid]. A pending entry for the same archive is folded in first.
     * The change is recorded BEFORE the first request, so a cancelled
     * caller (the dialog's screen closed) or a killed process still leaves
     * it for [flush]. Returns true when every tank is up to date; otherwise
     * the change stays recorded and false is returned.
     */
    suspend fun apply(
        baseUrl: String,
        arcid: String,
        oldTags: String,
        newTags: String,
        lister: TankLister,
        tankSync: TankSync,
    ): Boolean {
        val pending = get(baseUrl, arcid)
        val entry = Entry(baseUrl, arcid, pending?.oldTags ?: oldTags, newTags)
        put(entry)
        val done = try {
            syncAll(entry, lister, tankSync)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Tank tag sync failed; kept for a retry", e)
            false
        }
        if (done) removeIfUnchanged(entry)
        return done
    }

    /** Retry every pending entry once; a failure keeps the entry for the next attempt. */
    suspend fun flush(lister: TankLister, tankSync: TankSync) {
        for (entry in entries()) {
            try {
                if (syncAll(entry, lister, tankSync)) removeIfUnchanged(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Tank tag retry failed; kept for the next attempt", e)
            }
        }
    }

    /** [apply] against the real server. */
    suspend fun applyOnServer(client: OkHttpClient, baseUrl: String, arcid: String, oldTags: String, newTags: String) =
        apply(baseUrl, arcid, oldTags, newTags, serverLister(client), serverSync(client))

    /** [flush] against the real servers. The auth interceptor picks each host's key. */
    suspend fun flushToServers() {
        if (entries().isEmpty()) return
        val client = ServiceRegistry.networkModule.okHttpClient
        flush(serverLister(client), serverSync(client))
    }

    private fun serverLister(client: OkHttpClient) =
        TankLister { baseUrl, arcid -> LRRTankoubonApi.getArchiveTankoubons(client, baseUrl, arcid) }

    /** No per-tank Snackbar: the caller reports one pending message for the whole edit. */
    private fun serverSync(client: OkHttpClient) = TankSync { baseUrl, tankId, e ->
        TankTagSyncer.afterMemberTagsChanged(client, baseUrl, tankId, e.arcid, e.oldTags, e.newTags, notifyFailure = false)
    }

    /** Drops every pending change for [baseUrl] (its profile was deleted, audit SEC-16). */
    fun dropServer(baseUrl: String) {
        val p = prefs ?: return
        val keys = entries().filter { it.baseUrl == baseUrl }.map { key(it.baseUrl, it.arcid) }
        if (keys.isNotEmpty()) p.edit { keys.forEach { remove(it) } }
    }

    /**
     * An HTTP 4xx while listing (archive gone, or a server without the
     * tankoubon routes) means there is no tank to update: done.
     */
    private suspend fun syncAll(entry: Entry, lister: TankLister, tankSync: TankSync): Boolean {
        val tanks = try {
            lister.tanksOf(entry.baseUrl, entry.arcid)
        } catch (e: LRRHttpException) {
            if (e.code !in 400..499) throw e
            emptyList()
        }
        var allDone = true
        for (tankId in tanks) {
            if (tankSync.sync(entry.baseUrl, tankId, entry) == TankTagSyncer.Outcome.FAILED) allDone = false
        }
        return allDone
    }

    private fun get(baseUrl: String, arcid: String): Entry? {
        val key = key(baseUrl, arcid)
        return prefs?.getString(key, null)?.let { parse(key, it) }
    }

    private fun put(entry: Entry) {
        val value = json.encodeToString(Stored.serializer(), Stored(entry.oldTags, entry.newTags))
        prefs?.edit { putString(key(entry.baseUrl, entry.arcid), value) }
    }

    private fun remove(entry: Entry) {
        prefs?.edit { remove(key(entry.baseUrl, entry.arcid)) }
    }

    /** A newer edit may have replaced the entry meanwhile: keep that one. */
    private fun removeIfUnchanged(entry: Entry) {
        if (get(entry.baseUrl, entry.arcid) == entry) remove(entry)
    }

    private fun key(baseUrl: String, arcid: String) = arcid + SEP + baseUrl

    private fun parse(key: String, value: String): Entry? {
        val at = key.indexOf(SEP)
        if (at <= 0) return null
        val stored = decodeStored(value)
        return if (stored == null) null else Entry(key.substring(at + 1), key.substring(0, at), stored.old, stored.new)
    }

    private fun decodeStored(value: String): Stored? = try {
        json.decodeFromString(Stored.serializer(), value)
    } catch (e: IllegalArgumentException) {
        Log.e(TAG, "Dropping an unreadable pending tank tag entry", e)
        null
    }
}
