package com.lanraragi.reader.reading

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.lanraragi.reader.client.api.data.LRRTocEntry
import com.lanraragi.reader.client.api.data.LenientTocSerializer
import com.lanraragi.reader.client.api.lrrJson

/**
 * The server's table of contents, kept per `(profileId, arcid)` so a downloaded
 * archive still has its chapters offline. A read-only, re-fetchable hint: the
 * server's answer always replaces what is here, an archive with no chapters has
 * no entry, and pages stay as the server sent them because [ChapterIndex] checks
 * them against the real page count when they are shown.
 *
 * `profileId` is the archive's source profile
 * ([LocalReadingProgress.sourceProfile]), since two servers can hold the same arcid.
 */
class ChapterStore(private val prefs: SharedPreferences) {

    /** The kept chapters, or empty when there are none or the value is unreadable. */
    fun read(profileId: Long, arcid: String): List<LRRTocEntry> {
        val raw = prefs.getString(key(profileId, arcid), null) ?: return emptyList()
        return try {
            lrrJson.decodeFromString(LenientTocSerializer, raw)
        } catch (ignored: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException.
            emptyList()
        }
    }

    /** The server answered with [toc]: it replaces the entry, and an empty one clears it. */
    fun update(profileId: Long, arcid: String, toc: List<LRRTocEntry>) {
        val key = key(profileId, arcid)
        val value = if (toc.isEmpty()) null else lrrJson.encodeToString(LenientTocSerializer, toc)
        // Opening an archive again usually changes nothing: skip the disk write.
        if (prefs.getString(key, null) != value) prefs.edit { putString(key, value) }
    }

    /** Drops the entries of [rows], given as `(profileId, arcid)`. */
    fun remove(rows: Collection<Pair<Long, String>>) {
        drop(rows.map { (profileId, arcid) -> key(profileId, arcid) }.filter(prefs::contains))
    }

    /** Drops every entry of [profileId] (the profile was deleted, audit SEC-16). */
    fun removeProfile(profileId: Long) {
        val prefix = key(profileId, "")
        drop(prefs.all.keys.filter { it.startsWith(prefix) })
    }

    private fun drop(keys: List<String>) {
        if (keys.isNotEmpty()) prefs.edit { keys.forEach { remove(it) } }
    }

    private fun key(profileId: Long, arcid: String) = "$profileId$SEP$arcid"

    companion object {
        private const val PREFS = "reader_chapters"
        private const val SEP = ":"

        /** No shared instance to hold: the platform keeps one SharedPreferences per file. */
        fun of(context: Context): ChapterStore =
            ChapterStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
