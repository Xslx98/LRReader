package com.lanraragi.reader.download

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * [DownloadResumeBanner.InterruptedStore] backed by SharedPreferences, so the
 * interrupted-download record survives the process that wrote it (A47).
 * Writes use commit(): the record is taken just as a process may die again.
 */
class PrefsInterruptedStore(private val prefs: SharedPreferences) : DownloadResumeBanner.InterruptedStore {

    // getStringSet's result must not be modified; copy it out.
    override fun load(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet().orEmpty()

    override fun save(arcids: Set<String>) {
        prefs.edit(commit = true) { putStringSet(KEY, arcids.toSet()) }
    }

    private companion object {
        const val KEY = "download_interrupted_arcids"
    }
}
