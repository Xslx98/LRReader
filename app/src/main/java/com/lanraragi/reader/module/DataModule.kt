package com.lanraragi.reader.module

import android.content.Context
import android.util.Log
import androidx.collection.LruCache
import com.lanraragi.framework.beerbelly.SimpleDiskCache
import com.lanraragi.reader.FavouriteStatusRouter
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.domain.ArchiveDetail
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.DownloadDbRepository
import com.lanraragi.reader.dao.FavoritesRepository
import com.lanraragi.reader.dao.HistoryRepository
import com.lanraragi.reader.dao.ProfileRepository
import com.lanraragi.reader.dao.QuickSearchRepository
import com.lanraragi.reader.dao.SearchHistoryRepository
import com.lanraragi.reader.download.DownloadManager
import com.lanraragi.reader.client.api.ProfileLookupCache
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

    override val profileLookupCache: ProfileLookupCache by lazy {
        ProfileLookupCache(
            repo = profileRepository,
            scope = ServiceRegistry.coroutineModule.applicationScope,
        )
    }

    override val quickSearchRepository: QuickSearchRepository by lazy {
        QuickSearchRepository(AppDatabase.getInstance(context).browsingDao())
    }

    override val searchHistoryRepository: SearchHistoryRepository by lazy {
        val db = AppDatabase.getInstance(context)
        SearchHistoryRepository(db.browsingDao(), db)
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
