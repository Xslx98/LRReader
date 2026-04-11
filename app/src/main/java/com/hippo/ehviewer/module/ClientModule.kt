package com.hippo.ehviewer.module

import android.content.Context
import com.hippo.conaco.Conaco
import com.hippo.ehviewer.ImageBitmapHelper
import com.hippo.ehviewer.ServiceRegistry
import com.lanraragi.reader.client.api.LRRTagCache
import com.hippo.lib.image.Image
import java.io.File

/**
 * Manages client-side singletons: Conaco (image loader) and
 * ImageBitmapHelper (bitmap decoder).
 * Extracted from EhApplication to reduce its responsibility scope.
 */
class ClientModule(
    private val context: Context,
    private val networkModule: INetworkModule
) : IClientModule {

    init {
        ServiceRegistry.registerCacheable(LRRTagCache)
    }

    override val imageBitmapHelper: ImageBitmapHelper by lazy { ImageBitmapHelper() }

    override val conaco: Conaco<Image> by lazy {
        Conaco.Builder<Image>().apply {
            hasMemoryCache = true
            memoryCacheMaxSize = memoryCacheMaxSize()
            hasDiskCache = true
            diskCacheDir = File(context.cacheDir, "thumb")
            diskCacheMaxSize = 320 * 1024 * 1024 // 320MB
            okHttpClient = networkModule.okHttpClient
            objectHelper = imageBitmapHelper
            debug = false
        }.build()
    }

    override fun clearMemoryCache() {
        conaco.beerBelly?.clearMemory()
    }

    companion object {
        private const val MB = 1024L * 1024

        // Tier thresholds (per-app heap limit from Runtime.maxMemory())
        private const val TIER_LOW = 512 * MB      // < 512MB heap
        private const val TIER_MID = 1024 * MB      // < 1GB heap
        private const val TIER_HIGH = 3072 * MB     // < 3GB heap

        // Cache sizes per tier
        private const val CACHE_LOW = 16 * MB       // low-end devices
        private const val CACHE_MID = 32 * MB       // mid-range
        private const val CACHE_HIGH = 80 * MB      // flagships
        private const val CACHE_ULTRA = 128 * MB    // high-memory flagships

        internal fun memoryCacheMaxSize(): Int =
            tieredCacheSize(Runtime.getRuntime().maxMemory()).toInt()

        /**
         * Returns the image memory cache size for the given per-app heap limit.
         *
         * Tiers:
         * - `maxMemoryBytes < 512MB` → 16 MB
         * - `maxMemoryBytes < 1 GB`  → 32 MB
         * - `maxMemoryBytes < 3 GB`  → 80 MB
         * - `maxMemoryBytes >= 3 GB` → 128 MB
         */
        internal fun tieredCacheSize(maxMemoryBytes: Long): Long = when {
            maxMemoryBytes < TIER_LOW -> CACHE_LOW
            maxMemoryBytes < TIER_MID -> CACHE_MID
            maxMemoryBytes < TIER_HIGH -> CACHE_HIGH
            else -> CACHE_ULTRA
        }
    }
}
