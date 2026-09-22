package com.lanraragi.reader.download

import com.lanraragi.framework.unifile.UniFile
import com.lanraragi.reader.dao.DownloadDbRepository

/**
 * Finds directories in a download root that belong to no download in the
 * list. A directory is kept when the `DOWNLOAD_DIRNAME` pointer of any
 * download row names it — the pointer is the only arcid → directory link
 * since directories became title-named, so any name-pattern heuristic
 * (the old `<arcid>-` prefix match) would flag every download.
 *
 * Only directories are candidates; loose files and dot-entries (such as
 * `.nomedia`) are left alone. The caller confirms with the user before
 * deleting anything.
 */
class RedundantDownloadScanner(private val repo: DownloadDbRepository) {

    suspend fun scan(root: UniFile): List<UniFile> {
        val tracked = repo.getAllDownloadInfo().mapTo(HashSet()) { it.arcid }
        val claimed = repo.getAllDownloadDirnamePointers()
            .filterKeys { it in tracked }
            .values
            .mapTo(HashSet()) { it.lowercase() }
        val children = root.listFiles() ?: return emptyList()
        return children.filter { child ->
            val name = child.name
            child.isDirectory && name != null && !name.startsWith(".") && name.lowercase() !in claimed
        }.sortedBy { it.name }
    }
}
