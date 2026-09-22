package com.lanraragi.reader.download

import com.lanraragi.framework.lib.yorozuya.FileUtils
import com.lanraragi.framework.unifile.UniFile
import com.lanraragi.reader.dao.DownloadDbRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the `DOWNLOAD_DIRNAME` pointer — the only link from an arcid to
 * its title-named download directory ([DownloadDirNaming]).
 *
 * Two operations, deliberately separate:
 *  - [find] is lookup-only. Readers (reader routing, the downloads list,
 *    size sort, delete paths) must never mint a pointer: a pointer minted
 *    without its directory let two same-titled archives claim one name,
 *    because uniqueness was only checked against directories on disk.
 *  - [allocate] is the single place a pointer is created. Under one
 *    process-wide [Mutex] it picks a name free both on disk and among
 *    every existing pointer, creates the directory, and only then
 *    persists the pointer.
 *
 * A stored pointer that is not a single safe child segment
 * ([DownloadDirNaming.isSafeName]) is treated as absent, so nothing can
 * resolve — and later write into or delete — the root or its parent.
 */
class DownloadDirAllocator(
    private val repo: DownloadDbRepository,
    private val resolveRoot: (storedUri: String?) -> UniFile?,
) {

    private val mutex = Mutex()

    /**
     * The directory [arcid]'s pointer names under its recorded root, or
     * null when there is no (safe) pointer or the root is unavailable. The
     * directory itself may not exist.
     *
     * @param rootUriOverride the row's `downloadRootUri` when the caller
     *   already holds it — required on delete paths, where the row (and
     *   with it the stored root) may already be gone.
     */
    suspend fun find(arcid: String, rootUriOverride: String? = null): UniFile? {
        val dirname = storedPointer(arcid) ?: return null
        val root = resolveRoot(rootUriOverride ?: repo.getDownloadRootUri(arcid)) ?: return null
        return root.subFile(dirname)
    }

    /**
     * The existing directory for [arcid], creating directory and pointer
     * when there is none yet. Returns null when the root is unavailable or
     * the directory cannot be created.
     */
    suspend fun allocate(arcid: String, title: String?, rootUriOverride: String? = null): UniFile? =
        mutex.withLock {
            val root = resolveRoot(rootUriOverride ?: repo.getDownloadRootUri(arcid)) ?: return@withLock null
            storedPointer(arcid)?.let { name ->
                val dir = root.subFile(name)
                return@withLock if (dir?.ensureDir() == true) dir else null
            }
            // Case-insensitive: shared storage is, so "Title" and "title"
            // would be the same directory there.
            val claimed = repo.getAllDownloadDirnames().mapTo(HashSet()) { it.lowercase() }
            val name = DownloadDirNaming.uniqueName(DownloadDirNaming.baseName(arcid, title)) { candidate ->
                candidate.lowercase() in claimed || root.findFile(candidate) != null
            }
            val dir = root.createDirectory(name) ?: return@withLock null
            repo.putDownloadDirname(arcid, name)
            dir
        }

    private suspend fun storedPointer(arcid: String): String? {
        val stored = repo.getDownloadDirname(arcid) ?: return null
        // Some dirnames were written unsanitised by old versions; repair
        // the row only when sanitising actually changes it.
        val sanitized = FileUtils.sanitizeFilename(stored)
        if (!DownloadDirNaming.isSafeName(sanitized)) return null
        if (sanitized != stored) repo.putDownloadDirname(arcid, sanitized)
        return sanitized
    }
}
