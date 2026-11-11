package com.lanraragi.reader.reading

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.lanraragi.framework.lib.glgallery.GalleryView

/**
 * How a book pairs its pages in two-page reading, kept per `(profileId, id)`:
 * its "shift pairing by one" and the pairing parity the reader saw last
 * ([GalleryView.currentSpreadParity]), so a resume after a cache clear lands on
 * the same pairs. `id` is the arcid, or the tank id for a whole-tank session:
 * a tank is shifted once, never per member.
 *
 * The parity is stored relative to "the cover stands alone" (first page alone
 * xor shift): changing either flips the pairs, and the parity read back flips
 * with them. That keeps the planner's contract that a stored parity never
 * outvotes a new shift.
 *
 * One int per book: bit 0 the shift, bits 1-2 the relative parity + 1 (0 = none).
 */
class SpreadPairingStore(private val prefs: SharedPreferences) {

    fun isShifted(profileId: Long, id: String): Boolean = read(profileId, id) and 1 != 0

    fun setShifted(profileId: Long, id: String, shifted: Boolean) {
        write(profileId, id, (read(profileId, id) and 1.inv()) or (if (shifted) 1 else 0))
    }

    /**
     * The parity to land with while [coverAlone] (first page alone xor shift)
     * is in effect, or [GalleryView.PARITY_UNKNOWN].
     */
    fun parity(profileId: Long, id: String, coverAlone: Boolean): Int {
        val stored = (read(profileId, id) shr 1) - 1
        return if (stored < 0) GalleryView.PARITY_UNKNOWN else stored xor bit(coverAlone)
    }

    /** The reader shows a spread of [parity] with [coverAlone] in effect; -1 changes nothing. */
    fun putParity(profileId: Long, id: String, parity: Int, coverAlone: Boolean) {
        if (parity != 0 && parity != 1) return
        write(profileId, id, (read(profileId, id) and 1) or (((parity xor bit(coverAlone)) + 1) shl 1))
    }

    /** Drops every entry of [profileId] (the profile was deleted, audit SEC-16). */
    fun removeProfile(profileId: Long) {
        val prefix = key(profileId, "")
        val keys = prefs.all.keys.filter { it.startsWith(prefix) }
        if (keys.isNotEmpty()) prefs.edit { keys.forEach { remove(it) } }
    }

    private fun bit(value: Boolean) = if (value) 1 else 0

    private fun read(profileId: Long, id: String): Int = prefs.getInt(key(profileId, id), 0)

    private fun write(profileId: Long, id: String, value: Int) {
        val key = key(profileId, id)
        // Most page turns keep the parity: skip the disk write.
        if (prefs.getInt(key, 0) == value) return
        prefs.edit { if (value == 0) remove(key) else putInt(key, value) }
    }

    private fun key(profileId: Long, id: String) = "$profileId:$id"

    companion object {
        private const val PREFS = "reader_spread_pairing"

        /** No shared instance to hold: the platform keeps one SharedPreferences per file. */
        fun of(context: Context): SpreadPairingStore =
            SpreadPairingStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
