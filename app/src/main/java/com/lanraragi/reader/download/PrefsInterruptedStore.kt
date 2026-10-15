package com.lanraragi.reader.download

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * [DownloadResumeBanner.InterruptedStore] backed by SharedPreferences, so the
 * interrupted-download record survives the process that wrote it (A47).
 * Recording uses commit(): the record is taken just as a process may die
 * again. Removals use apply(): they run on the main thread, and a lost one
 * only re-offers a download that was already resumed.
 *
 * The arcids are kept as one newline-joined string, not a string set, so
 * they keep the order they were queued in and a resume restarts them in that
 * order. A record written by an older version as a string set is still read.
 */
class PrefsInterruptedStore(private val prefs: SharedPreferences) : DownloadResumeBanner.InterruptedStore {

    override fun load(): Set<String> {
        val ordered = prefs.getString(KEY, null)
        if (ordered != null) return ordered.split(SEPARATOR).filterTo(LinkedHashSet()) { it.isNotEmpty() }
        // getStringSet's result must not be modified; copy it out.
        return prefs.getStringSet(LEGACY_SET_KEY, emptySet())?.toSet().orEmpty()
    }

    override fun save(arcids: Set<String>) {
        prefs.edit(commit = true) { write(arcids) }
    }

    override fun saveAfterRemoval(arcids: Set<String>) {
        prefs.edit { write(arcids) }
    }

    private fun SharedPreferences.Editor.write(arcids: Set<String>) {
        putString(KEY, arcids.joinToString(SEPARATOR))
        remove(LEGACY_SET_KEY)
    }

    private companion object {
        const val KEY = "download_interrupted_arcids_ordered"
        const val LEGACY_SET_KEY = "download_interrupted_arcids"
        const val SEPARATOR = "\n"
    }
}
