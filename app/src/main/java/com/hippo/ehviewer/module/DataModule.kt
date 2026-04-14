package com.hippo.ehviewer.module

import android.content.Context
import android.util.Log
import androidx.collection.LruCache
import com.hippo.beerbelly.SimpleDiskCache
import com.hippo.ehviewer.FavouriteStatusRouter
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.DownloadDbRepository
import com.hippo.ehviewer.dao.FavoritesRepository
import com.hippo.ehviewer.dao.HistoryRepository
import com.hippo.ehviewer.dao.ProfileRepository
import com.hippo.ehviewer.dao.QuickSearchRepository
import com.hippo.ehviewer.download.DownloadManager
import java.io.File

/**
 * Manages data-layer singletons: DownloadManager, GalleryDetailCache,
 * SpiderInfoCache, and FavouriteStatusRouter.
 * Extracted from EhApplication to reduce its responsibility scope.
 */
class DataModule(private val context: Context) : IDataModule, Cacheable {

    companion object {
        private const val TAG = "DataModule"
    }

    override val favouriteStatusRouter: FavouriteStatusRouter by lazy { FavouriteStatusRouter() }

    override val downloadManager: DownloadManager by lazy { DownloadManager(context) }

    override val historyRepository: HistoryRepository by lazy {
        HistoryRepository(AppDatabase.getInstance(context).browsingDao())
    }

    override val profileRepository: ProfileRepository by lazy {
        ProfileRepository(AppDatabase.getInstance(context).miscDao())
    }

    override val quickSearchRepository: QuickSearchRepository by lazy {
        QuickSearchRepository(AppDatabase.getInstance(context).browsingDao())
    }

    override val favoritesRepository: FavoritesRepository by lazy {
        FavoritesRepository(AppDatabase.getInstance(context).browsingDao())
    }

    override val downloadDbRepository: DownloadDbRepository by lazy {
        DownloadDbRepository(
            AppDatabase.getInstance(context).downloadDao(),
            AppDatabase.getInstance(context)
        )
    }

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
        try { spiderInfoCache.clear() } catch (e: Exception) { Log.w(TAG, "Failed to clear spider info cache", e) }
    }
}
