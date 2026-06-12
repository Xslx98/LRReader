package com.hippo.ehviewer.ui.scene.download

import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-arcid cache of resolved download directories for the downloads list.
 *
 * Every thumbnail bind needs the archive's download dir — a Room lookup via
 * [SpiderDen.getGalleryDownloadDir] plus a possible directory listing. Each
 * bind used to start that resolution from scratch (one coroutine per
 * ThumbDataContainer), so a fast scroll churned the DB with identical
 * lookups. This cache keys the in-flight/completed future by arcid so each
 * archive resolves at most once per ViewModel lifetime.
 *
 * A future that resolves to `null` (storage unavailable, permission lost)
 * is evicted right away so the next bind retries instead of pinning the
 * failure for the rest of the session — matching the pre-cache behavior
 * where every bind re-resolved.
 *
 * [scope] must outlive pending resolutions (the application ioScope, not
 * viewModelScope): consumers block on the future from Conaco's I/O threads,
 * and a cancelled-before-completion future would block such a thread
 * forever. The finally below completes the future even on cancellation as
 * a second line of defence.
 */
class DownloadDirCache(
    private val scope: CoroutineScope,
    private val resolver: suspend (DownloadInfo) -> UniFile? = { info ->
        SpiderDen.getGalleryDownloadDir(info.arcid, info.title, rootUriOverride = info.downloadRootUri)
    },
) {
    private val cache = ConcurrentHashMap<String, CompletableFuture<UniFile?>>()

    /** The (possibly in-flight) directory resolution for [info]'s arcid. */
    fun futureFor(info: DownloadInfo): CompletableFuture<UniFile?> {
        cache[info.arcid]?.let { return it }
        val future = CompletableFuture<UniFile?>()
        val existing = cache.putIfAbsent(info.arcid, future)
        if (existing != null) return existing
        scope.launch {
            var dir: UniFile? = null
            try {
                dir = resolver(info)
            } catch (e: Exception) {
                // Resolution failure is represented as an unresolved (null) dir.
            } finally {
                if (dir == null) cache.remove(info.arcid, future)
                future.complete(dir)
            }
        }
        return future
    }

    /** Drop the cached resolution for [arcid] (single remove / replace paths). */
    fun invalidate(arcid: String) {
        cache.remove(arcid)
    }

    /** Drop every cached resolution (list reload / bulk delete paths). */
    fun clear() {
        cache.clear()
    }
}
