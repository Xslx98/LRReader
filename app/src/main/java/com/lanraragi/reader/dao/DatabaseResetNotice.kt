package com.lanraragi.reader.dao

import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File

/**
 * Tells the user once that a corrupt database was moved aside and replaced
 * by an empty one (audit 2026-10-06d REL-02). [QuarantiningOpenHelperFactory]
 * does that silently while Room opens the file, so the app used to come up
 * with no servers, history or favourites and no word why.
 *
 * The quarantined files carry their timestamp in the name; the newest one
 * the user has acknowledged is kept in [prefs], so the notice survives a
 * process death before the user saw it and is shown for each new quarantine
 * only. A reset the user chose (boot-failure dialog) is marked seen at once.
 */
object DatabaseResetNotice {
    /** Newest quarantine timestamp the user has acknowledged. */
    const val KEY_SEEN_STAMP = "db_reset_notice_seen_stamp"

    /** Timestamp of a quarantine of [dbFile] the user was not told about, or null. */
    fun pendingStamp(dbFile: File, prefs: SharedPreferences): Long? {
        val newest = DatabaseQuarantine.newestStamp(dbFile)
        if (newest == null || newest <= prefs.getLong(KEY_SEEN_STAMP, 0L)) return null
        return newest
    }

    /** Records [stamp] as acknowledged; [commit] when the process is about to restart. */
    fun markSeen(prefs: SharedPreferences, stamp: Long, commit: Boolean = false) {
        if (stamp <= prefs.getLong(KEY_SEEN_STAMP, 0L)) return
        prefs.edit(commit = commit) { putLong(KEY_SEEN_STAMP, stamp) }
    }
}
