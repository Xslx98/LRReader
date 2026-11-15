package com.lanraragi.reader.dao

import androidx.room.TypeConverter

/** Why a download failed, as far as the app can tell (audit 2026-10-04 C21). */
enum class DownloadFailureReason {
    /** The device ran out of storage. */
    NO_SPACE,

    /** The server refused the request (401/403): API key missing or rotated. */
    AUTH,

    /** The archive or page no longer exists on the server (404/410). */
    NOT_FOUND,

    /** The server answered 5xx or 429. */
    SERVER,

    /** The connection failed or timed out while the network was up. */
    NETWORK,

    /** The server answered, but not with a usable image. */
    CORRUPT,

    UNKNOWN,
}

/**
 * Room TypeConverter pair storing [DownloadFailureReason] by name in
 * `DOWNLOAD_FAILURE_REASON`. An unknown name (a row written by a newer build)
 * reads as null rather than throwing.
 */
object DownloadFailureReasonConverter {
    @TypeConverter
    @JvmStatic
    fun toName(reason: DownloadFailureReason?): String? = reason?.name

    @TypeConverter
    @JvmStatic
    fun fromName(name: String?): DownloadFailureReason? =
        name?.let { n -> DownloadFailureReason.entries.firstOrNull { it.name == n } }
}
