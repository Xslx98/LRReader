package com.hippo.ehviewer.download

import androidx.room.TypeConverter

/**
 * Closed set of download lifecycle states.
 *
 * Replaces the loose `Int` constants that used to live on
 * `DownloadInfo.Companion` so `when` blocks over a download state get
 * compile-time exhaustiveness checks.
 *
 * The integer [code] is the on-disk representation: it is the value
 * stored in the `STATE` column of the `DOWNLOADS` table and the value
 * carried over Parcel and CSV. The numeric assignments are frozen —
 * changing them would require a schema migration plus a CSV format
 * bump.
 */
enum class DownloadState(val code: Int) {
    /** Sentinel for "no row in the DOWNLOADS table for this archive". */
    INVALID(-1),

    /** Row exists, never queued. */
    NONE(0),

    /** Queued; waiting for an open Scheduler slot. */
    WAIT(1),

    /** Worker is actively pulling pages. */
    DOWNLOAD(2),

    /** Worker finished successfully. */
    FINISH(3),

    /** Worker stopped with an error. */
    FAILED(4);

    companion object {
        /**
         * Map a wire/persisted [code] back to its [DownloadState], or
         * [INVALID] when the code is unrecognised. The fallback keeps
         * forward-compat with rows written by future builds we have
         * not been taught about yet.
         */
        @JvmStatic
        fun fromCode(code: Int): DownloadState =
            entries.firstOrNull { it.code == code } ?: INVALID
    }
}

/** Room TypeConverter pair for storing [DownloadState] as INTEGER. */
object DownloadStateConverter {
    @TypeConverter
    @JvmStatic
    fun toCode(state: DownloadState): Int = state.code

    @TypeConverter
    @JvmStatic
    fun fromCode(code: Int): DownloadState = DownloadState.fromCode(code)
}
