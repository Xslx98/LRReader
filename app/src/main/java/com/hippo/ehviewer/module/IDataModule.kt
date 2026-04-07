package com.hippo.ehviewer.module

import androidx.collection.LruCache
import com.hippo.beerbelly.SimpleDiskCache
import com.hippo.ehviewer.FavouriteStatusRouter
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.download.DownloadManager

/**
 * Abstraction over [DataModule] to allow ServiceRegistry consumers to depend on the
 * contract rather than the concrete implementation. Enables test-time substitution with
 * in-memory stubs and fake download managers.
 */
interface IDataModule {

    /** Fan-out channel notifying listeners of favourite-slot changes. */
    val favouriteStatusRouter: FavouriteStatusRouter

    /** Global download state manager and persistence gateway. */
    val downloadManager: DownloadManager

    /** LRU cache for [GalleryDetail] objects keyed by gid. */
    val galleryDetailCache: LruCache<Long, GalleryDetail>

    /** Small disk cache holding per-gallery spider state for preloading. */
    val spiderInfoCache: SimpleDiskCache

    /** Evicts every entry from [galleryDetailCache]. */
    fun clearGalleryDetailCache()
}
