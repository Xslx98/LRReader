package com.lanraragi.reader.archive

import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.server.ServerConnection
import com.lanraragi.reader.util.suspendRunCatching
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/**
 * Uploads a picked archive file to a LANraragi server, with a cheap
 * duplicate check first. Runs in the caller's context; the caller owns
 * feedback, cancellation and the server it names.
 */
object ArchiveUploadService {

    /** How an upload ended: [duplicate] = already on the server, nothing was sent. */
    class Outcome(val arcid: String, val duplicate: Boolean)

    /**
     * The archive id (SHA-1 of the first 512 KB) is computed locally and
     * probed against [conn]'s server; an existing archive returns a
     * [Outcome.duplicate] outcome without transferring — or even fully
     * copying — the file. Otherwise the file is staged under [cacheDir] and
     * uploaded, reporting byte progress to [onProgress] (0..100, starting
     * with 0, each value once). The staging directory is removed on every
     * exit. Failures propagate.
     *
     * @param openStream must open a *fresh* stream each call: it is invoked
     *   twice, once to fingerprint the leading bytes, once to copy the file
     */
    suspend fun upload(
        conn: ServerConnection,
        displayName: String,
        cacheDir: File,
        openStream: () -> InputStream?,
        onProgress: (percent: Int) -> Unit,
    ): Outcome {
        val arcid = (openStream() ?: throw IOException("Cannot open file"))
            .use { LRRArchiveApi.computeArchiveId(it) }

        // Best-effort: an inconclusive probe (network/proxy error) is
        // treated as "not a confirmed duplicate" so the upload still runs
        // and the server's own check remains the backstop.
        val isDuplicate = suspendRunCatching { LRRArchiveApi.archiveExists(conn.client, conn.baseUrl, arcid) }
            .getOrDefault(false)
        if (isDuplicate) return Outcome(arcid, duplicate = true)

        // The multipart filename becomes the archive's server-side
        // name, so the copy keeps the picked name — but inside a fresh
        // private directory and reduced to one safe path segment: a
        // provider's DISPLAY_NAME is untrusted ("../databases/x.db").
        val dir = Files.createTempDirectory(cacheDir.toPath(), "upload").toFile()
        try {
            val target = File(dir, safeUploadFileName(displayName))
            openStream().use { input ->
                if (input == null) throw IOException("Cannot open file")
                FileOutputStream(target).use { fos -> input.copyTo(fos) }
            }

            onProgress(0)
            val checksum = LRRArchiveApi.computeFileChecksum(target)
            var lastPercent = -1
            val arcidUploaded = LRRArchiveApi.uploadArchive(
                ServiceRegistry.networkModule.uploadClient,
                conn.baseUrl,
                target,
                fileChecksum = checksum,
                progressListener = { written, total ->
                    if (total > 0) {
                        val pct = (written * PERCENT_MAX / total).toInt().coerceIn(0, PERCENT_MAX)
                        if (pct != lastPercent) {
                            lastPercent = pct
                            onProgress(pct)
                        }
                    }
                }
            )
            return Outcome(arcidUploaded.ifEmpty { arcid }, duplicate = false)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Upload progress is reported on a 0..100 scale. */
    private const val PERCENT_MAX = 100

    /** Name used when the picked document's name is unusable. */
    internal const val FALLBACK_UPLOAD_NAME = "upload_archive"

    /** Upper bound on the staged file name, below common 255-byte filesystem limits. */
    private const val MAX_UPLOAD_NAME_CHARS = 120

    /**
     * Reduce an untrusted display name to one plain path segment: keep the
     * part after the last `/` or `\`, drop control characters, and fall back
     * to [FALLBACK_UPLOAD_NAME] for blank, `.` or `..` names.
     */
    internal fun safeUploadFileName(raw: String?): String {
        val lastSegment = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val cleaned = lastSegment.filterNot { it.isISOControl() }.trim().takeLast(MAX_UPLOAD_NAME_CHARS)
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") FALLBACK_UPLOAD_NAME else cleaned
    }
}
