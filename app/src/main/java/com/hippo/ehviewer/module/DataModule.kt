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
class DataModule(private val context: Context) {

    val favouriteStatusRouter: FavouriteStatusRouter by lazy { FavouriteStatusRouter() }

    val downloadManager: DownloadManager by lazy { DownloadManager(context) }

    val galleryDetailCache: LruCache<Long, GalleryDetail> by lazy {
        LruCache<Long, GalleryDetail>(25).also { cache ->
            favouriteStatusRouter.addListener { gid, slot ->
                cache[gid]?.let { it.favoriteSlot = slot }
            }
        }
    }

    val spiderInfoCache: SimpleDiskCache by lazy {
        SimpleDiskCache(File(context.cacheDir, "spider_info"), 5 * 1024 * 1024) // 5MB
    }

    // --- User tag list (in-memory cache) ---

    @Volatile
    var userTagList: UserTagList? = null
        private set

    fun saveUserTagList(@NonNull list: UserTagList) {
        userTagList = list
    }

    fun clearGalleryDetailCache() {
        galleryDetailCache.evictAll()
    }
}
