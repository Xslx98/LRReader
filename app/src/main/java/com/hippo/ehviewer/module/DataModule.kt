package com.hippo.ehviewer.module

import android.content.Context
import androidx.annotation.NonNull
import androidx.collection.LruCache
import com.hippo.beerbelly.SimpleDiskCache
import com.hippo.ehviewer.FavouriteStatusRouter
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.userTag.UserTagList
import com.hippo.ehviewer.download.DownloadManager
import java.io.File

/**
 * Manages data-layer singletons: DownloadManager, GalleryDetailCache,
 * SpiderInfoCache, FavouriteStatusRouter, and user tag list.
 * Extracted from EhApplication to reduce its responsibility scope.
 */
class DataModule(private val context: Context) : IDataModule {

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

    // --- User tag list (in-memory cache) ---

    @Volatile
    override var userTagList: UserTagList? = null
        private set

    override fun saveUserTagList(@NonNull list: UserTagList) {
        userTagList = list
    }

    override fun clearGalleryDetailCache() {
        galleryDetailCache.evictAll()
    }
}
