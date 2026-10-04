package com.lanraragi.reader.download

import android.util.Log
import com.lanraragi.framework.unifile.UniFile
import com.lanraragi.reader.dao.DownloadInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.FileOutputStream
import java.io.IOException

/**
 * `.lrr.json` written into every download directory (audit 2026-10-04 C05,
 * first slice). Directories are title-named, so before this marker the
 * `DOWNLOAD_DIRNAME` row was the only arcid → directory link: once the
 * database was lost nothing on disk said which archive a directory held.
 * The marker makes a directory self-describing, so it can be recognised
 * by [RedundantDownloadScanner] and re-linked by a later restore.
 */
@Serializable
data class DownloadDirMarker(
    val version: Int = VERSION,
    val arcid: String,
    val serverProfileId: Long,
    val title: String? = null,
    val pagecount: Int? = null,
) {
    companion object {
        const val FILE_NAME = ".lrr.json"
        const val VERSION = 1
        private const val TMP_NAME = "$FILE_NAME.tmp"
        private const val TAG = "DownloadDirMarker"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun of(info: DownloadInfo, pagecount: Int? = info.pagecount.takeIf { it > 0 }) =
            DownloadDirMarker(
                arcid = info.arcid,
                serverProfileId = info.serverProfileId,
                title = info.title,
                pagecount = pagecount,
            )

        /** The marker in [dir], or null when it is absent or unreadable. */
        fun read(dir: UniFile): DownloadDirMarker? {
            val file = dir.findFile(FILE_NAME) ?: return null
            return try {
                file.openInputStream().use { json.decodeFromString(serializer(), it.readBytes().decodeToString()) }
            } catch (e: IOException) {
                unreadable(e)
            } catch (e: IllegalArgumentException) {
                // Also covers SerializationException (a subclass).
                unreadable(e)
            }
        }

        private fun unreadable(e: Exception): DownloadDirMarker? {
            Log.w(TAG, "Unreadable download dir marker", e)
            return null
        }

        /**
         * Writes [marker] into [dir] through a synced temp file and a rename,
         * so a crash never leaves a truncated marker. A marker that already
         * holds the same content is left untouched.
         *
         * @return true when [dir] holds [marker] afterwards.
         */
        fun write(dir: UniFile, marker: DownloadDirMarker): Boolean {
            if (read(dir) == marker) return true
            dir.findFile(TMP_NAME)?.delete()
            val tmp = dir.createFile(TMP_NAME) ?: return false
            try {
                tmp.openOutputStream().use { out ->
                    out.write(json.encodeToString(serializer(), marker).encodeToByteArray())
                    out.flush()
                    (out as? FileOutputStream)?.fd?.sync()
                }
            } catch (e: IOException) {
                Log.w(TAG, "Marker write failed", e)
                tmp.delete()
                return false
            }
            // A file:// rename replaces the old marker atomically; a document
            // provider would pick a new name instead, so drop the old one first.
            if (!UniFile.isFileUri(dir.uri)) dir.findFile(FILE_NAME)?.delete()
            if (tmp.renameTo(FILE_NAME)) return true
            tmp.delete()
            return false
        }
    }
}
