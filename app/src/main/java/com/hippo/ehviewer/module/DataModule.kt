package com.hippo.ehviewer.module

import android.content.Context
import androidx.collection.LruCache
import com.hippo.beerbelly.SimpleDiskCache
import com.hippo.ehviewer.FavouriteStatusRouter
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.download.DownloadManager
import java.io.File

/**
 * Manages data-layer singletons: DownloadManager, GalleryDetailCache,
 * SpiderInfoCache, and FavouriteStatusRouter.
 * Extracted from EhApplication to reduce its responsibility scope.
 */
class DataModule(private val context: Context) : IDataModule, Cacheable {

    override val favouriteStatusRouter: FavouriteStatusRouter by lazy { FavouriteStatusRouter() }

    override val downloadManager: DownloadManager by lazy { DownloadManager(context) }

    override val galleryDetailCache: LruCache<Long, GalleryDetail> by lazy {
        LruCache<Long, GalleryDetail>(150).also { cache ->
            favouriteStatusRouter.addListener { gid, slot ->
                cache[gid]?.let { it.favoriteSlot = slot }
            }
        }
    }

    override val spiderInfoCache: SimpleDiskCache by lazy {
        SimpleDiskCache(File(context.cacheDir, "spider_info"), 5 * 1024 * 1024) // 5MB
    }

    override fun clearGalleryDetailCache() {
        galleryDetailCache.evictAll()
    }

    override fun clearCache() {
        galleryDetailCache.evictAll()
        try { spiderInfoCache.clear() } catch (_: Exception) {}
    }
}
