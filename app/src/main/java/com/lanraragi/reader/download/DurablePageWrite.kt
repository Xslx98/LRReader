package com.lanraragi.reader.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * What a page write into a **download directory** must guarantee, whoever
 * writes it: the worker, or the reader's hybrid session
 * ([com.lanraragi.reader.gallery.HybridPageStore]) writing into the same
 * directory (audit 2026-10-06c REL-04).
 *
 * The worker's resume branch skips any page with a valid header and a
 * plausible size, and a FINISH download is never re-checked. So a page that
 * lands there must be fsynced before its rename (a power loss otherwise
 * leaves a renamed file whose tail after the header is zeros), must not push
 * the volume below the worker's free-space floor, and must respect the
 * worker's per-page size cap. The reader's own page cache is disposable and
 * keeps its cheaper unsynced write.
 *
 * @param usableBytes bytes still allocatable on the volume holding a directory.
 * @param syncFile forces a written file's data to the storage device.
 * @param maxPageBytes per-page size cap; a test seam, production uses [MAX_PAGE_SIZE].
 */
class DurablePageWrite(
    private val usableBytes: (File) -> Long,
    private val syncFile: (FileOutputStream) -> Unit,
    val maxPageBytes: Long = MAX_PAGE_SIZE,
) {

    /** Below [MIN_FREE_BYTES] on [dir]'s volume no page is written there. */
    fun hasRoom(dir: File): Boolean = usableBytes(dir) >= MIN_FREE_BYTES

    /** Throws when the volume holding [dir] is below the free-space floor. */
    @Throws(IOException::class)
    fun checkRoom(dir: File) {
        if (!hasRoom(dir)) throw IOException("Storage full")
    }

    /** Throws once a page has grown past [maxPageBytes]. */
    @Throws(IOException::class)
    fun checkSize(bytes: Long) {
        if (bytes > maxPageBytes) {
            throw IOException("Page exceeds maximum size limit (${maxPageBytes / 1024 / 1024} MB)")
        }
    }

    /** Flushes [out] and forces its bytes to disk; call before renaming the temp file. */
    @Throws(IOException::class)
    fun sync(out: FileOutputStream) {
        out.flush()
        syncFile(out)
    }

    companion object {
        /** Below this much free space no further page is downloaded (audit C21). */
        const val MIN_FREE_BYTES = 64L * 1024 * 1024

        /** Per-page size cap. */
        const val MAX_PAGE_SIZE = 200L * 1024 * 1024

        /** The worker's free-space query and a real fsync. */
        val Production = DurablePageWrite(
            usableBytes = { dir -> LRRDownloadWorker.Env.Production.usableBytes(dir) },
            syncFile = { out -> out.fd.sync() },
        )
    }
}
