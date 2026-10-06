package com.lanraragi.reader.gallery

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.lanraragi.reader.client.api.LRRAuthManager

/**
 * The local reading-progress store (`reading_progress` SharedPreferences) behind
 * [GalleryProvider2]'s static progress functions (audit 2026-10-04 C37).
 *
 * - Keys are `<profileId>:<arcid>` → 0-indexed page and `<profileId>:<arcid>_ts`
 *   → saved-at epoch seconds, so the same content-hash arcid on two servers keeps
 *   two positions (REL-16). The profile is the active one; a reader always reads
 *   for it. Pre-C37 keys (bare arcid) move to the active profile once
 *   ([migrateLegacy]).
 * - A page turn no longer copies the whole map: the entry count is kept in memory
 *   and the trim scan runs only above [MAX_ENTRIES] (PERF-11). Saving the page
 *   that is already stored is skipped.
 */
internal object LocalReadingProgress {

    const val PREFS = "reading_progress"
    const val TS_SUFFIX = "_ts"
    const val SEP = ":"

    /** Archive-count cap (2 keys each). */
    const val MAX_ENTRIES = 500

    /** Entries kept after a trim (hysteresis so trims stay rare). */
    const val TRIM_TARGET = 400

    /** The profile progress belongs to; tests pin it. */
    @Volatile
    internal var profileId: () -> Long = ::activeProfileId

    /** Archives in the store, counted once per process; -1 = not counted yet. */
    @Volatile
    private var entries = -1

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun key(profileId: Long, arcid: String): String = "$profileId$SEP$arcid"

    /** @return true when something was written */
    fun save(ctx: Context, arcid: String, page: Int, nowSeconds: Long): Boolean {
        val prefs = prefs(ctx)
        val key = key(profileId(), arcid)
        val known = prefs.contains(key)
        if (known && prefs.getInt(key, -1) == page) return false
        prefs.edit {
            putInt(key, page)
            putLong(key + TS_SUFFIX, nowSeconds)
        }
        if (!known) countNewEntry(prefs, key)
        return true
    }

    fun load(ctx: Context, arcid: String): Int = prefs(ctx).getInt(key(profileId(), arcid), 0)

    fun loadTimestamp(ctx: Context, arcid: String): Long =
        prefs(ctx).getLong(key(profileId(), arcid) + TS_SUFFIX, 0L)

    fun clear(ctx: Context, arcid: String) {
        val key = key(profileId(), arcid)
        prefs(ctx).edit {
            remove(key)
            remove(key + TS_SUFFIX)
        }
        entries = -1
    }

    private fun countNewEntry(prefs: SharedPreferences, activeKey: String) {
        val count = if (entries < 0) countEntries(prefs.all) else entries + 1
        entries = count
        if (count > MAX_ENTRIES) {
            trim(prefs, activeKey)
            entries = countEntries(prefs.all)
        }
    }

    private fun countEntries(all: Map<String, *>): Int = all.keys.count { !it.endsWith(TS_SUFFIX) }

    /**
     * Prunes the oldest entries (by `_ts`; entries without one count as oldest)
     * down to [TRIM_TARGET] when above [MAX_ENTRIES]. [activeKey] — the archive
     * being read right now — is never pruned.
     */
    fun trim(prefs: SharedPreferences, activeKey: String) {
        val all = prefs.all
        val keys = all.keys.filter { !it.endsWith(TS_SUFFIX) }
        if (keys.size <= MAX_ENTRIES) return
        val toRemove = keys
            .sortedBy { (all[it + TS_SUFFIX] as? Long) ?: 0L }
            .take(keys.size - TRIM_TARGET)
            .filter { it != activeKey }
        prefs.edit {
            for (key in toRemove) {
                remove(key)
                remove(key + TS_SUFFIX)
            }
        }
    }

    /**
     * Moves pre-C37 entries (bare arcid keys) under [profileId]. An entry already
     * stored for that profile wins. Idempotent; cheap when nothing is left to move.
     * @return how many entries moved
     */
    fun migrateLegacy(prefs: SharedPreferences, profileId: Long): Int {
        val all = prefs.all
        val legacy = all.keys.filter { SEP !in it && !it.endsWith(TS_SUFFIX) && all[it] is Int }
        val orphanTs = all.keys.filter { SEP !in it && it.endsWith(TS_SUFFIX) && it.removeSuffix(TS_SUFFIX) !in all }
        if (legacy.isEmpty() && orphanTs.isEmpty()) return 0
        var moved = 0
        prefs.edit {
            for (arcid in legacy) {
                val target = key(profileId, arcid)
                if (target !in all) {
                    putInt(target, all[arcid] as Int)
                    (all[arcid + TS_SUFFIX] as? Long)?.let { putLong(target + TS_SUFFIX, it) }
                    moved++
                }
                remove(arcid)
                remove(arcid + TS_SUFFIX)
            }
            orphanTs.forEach { remove(it) }
        }
        entries = -1
        return moved
    }

    private fun activeProfileId(): Long = LRRAuthManager.getActiveProfileId()

    internal fun resetForTest() {
        entries = -1
        profileId = ::activeProfileId
    }
}
