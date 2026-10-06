package com.lanraragi.reader.backup

import android.content.Context
import androidx.core.net.toUri
import com.lanraragi.framework.unifile.UniFile
import com.lanraragi.reader.backup.BackupMerge.Relink
import com.lanraragi.reader.download.DownloadDirMarker

/**
 * Finds backed-up downloads whose files are still on disk (ruling R18: only those
 * come back). Download directories are title-named, so the `.lrr.json` marker in
 * each one is what says which archive it holds.
 *
 * @param listDirs a root URI's child directories as (name, marker arcid or null)
 */
class DownloadRelinker(private val listDirs: (rootUri: String) -> List<Pair<String, String?>>) {

    /** arcid → directory, from every root in [rootUris]; the first root listing an arcid wins. */
    fun index(rootUris: Collection<String>): Map<String, Relink> {
        val found = LinkedHashMap<String, Relink>()
        for (root in rootUris.distinct()) {
            for ((name, arcid) in listDirs(root)) {
                if (arcid != null && arcid !in found) found[arcid] = Relink(root, name)
            }
        }
        return found
    }

    companion object {
        fun onDisk(context: Context): DownloadRelinker = DownloadRelinker { rootUri ->
            val root = UniFile.fromUri(context, rootUri.toUri())
            root?.listFiles().orEmpty()
                .filter { it.isDirectory && it.name?.startsWith(".") == false }
                .map { dir -> dir.name.orEmpty() to DownloadDirMarker.read(dir)?.arcid }
        }
    }
}
