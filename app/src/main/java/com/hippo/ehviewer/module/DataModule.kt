package com.hippo.ehviewer.module

import android.content.Context
import android.util.Log
import androidx.collection.LruCache
import com.hippo.beerbelly.SimpleDiskCache
import com.hippo.ehviewer.FavouriteStatusRouter
import com.lanraragi.reader.domain.ArchiveDetail
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
        val db = AppDatabase.getInstance(context)
        HistoryRepository(db.archiveLocalStateDao(), db)
    }

    override val profileRepository: ProfileRepository by lazy {
        ProfileRepository(AppDatabase.getInstance(context).miscDao())
    }

    override val quickSearchRepository: QuickSearchRepository by lazy {
        QuickSearchRepository(AppDatabase.getInstance(context).browsingDao())
    }

    override val favoritesRepository: FavoritesRepository by lazy {
        val db = AppDatabase.getInstance(context)
        FavoritesRepository(db.archiveLocalStateDao(), db)
    }

    override val downloadDbRepository: DownloadDbRepository by lazy {
        val db = AppDatabase.getInstance(context)
        DownloadDbRepository(
            db.archiveLocalStateDao(),
            db.downloadDao(),
            db,
        )
    }

    override val archiveDetailCache: LruCache<String, ArchiveDetail> by lazy {
        LruCache<String, ArchiveDetail>(150)
    }

    override val spiderInfoCache: SimpleDiskCache by lazy {
        SimpleDiskCache(File(context.cacheDir, "spider_info"), 5 * 1024 * 1024) // 5MB
    }

    override fun clearArchiveDetailCache() {
        archiveDetailCache.evictAll()
    }

    override fun clearCache() {
        archiveDetailCache.evictAll()
        try { spiderInfoCache.clear() } catch (e: Exception) { Log.w(TAG, "Failed to clear spider info cache", e) }
    }
}
