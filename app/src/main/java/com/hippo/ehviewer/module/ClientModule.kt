package com.hippo.ehviewer.module

import android.content.Context
import com.hippo.conaco.Conaco
import com.hippo.ehviewer.ImageBitmapHelper
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.gallery.ReaderPageCache
import com.lanraragi.reader.client.api.LRRTagCache
import com.lanraragi.reader.client.api.LrrFileListCache
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
        ServiceRegistry.registerCacheable(LrrFileListCache)
        ServiceRegistry.registerCacheable(ReaderPageCache)
    }

    override val imageBitmapHelper: ImageBitmapHelper by lazy { ImageBitmapHelper() }

    override val conaco: Conaco<Image> by lazy {
        Conaco.Builder<Image>().apply {
            hasMemoryCache = true
            memoryCacheMaxSize = memoryCacheMaxSize()
            hasDiskCache = true
            diskCacheDir = File(context.cacheDir, "thumb")
            diskCacheMaxSize = diskCacheMaxSize()
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

        // Memory cache sizes per tier — kept conservative on low-end heaps
        // because the previous 16 MB ceiling on a 256 MB heap was 6.25% of
        // total app memory, large enough to push the heap toward GC pressure
        // when paired with adapter Bitmaps and Glide-style decode buffers.
        private const val CACHE_LOW = 8 * MB        // low-end devices
        private const val CACHE_MID = 32 * MB       // mid-range
        private const val CACHE_HIGH = 80 * MB      // flagships
        private const val CACHE_ULTRA = 128 * MB    // high-memory flagships

        // Disk cache sizes per tier — small devices typically also have
        // limited internal storage, so cap thumbnail cache aggressively
        // there. High-end devices keep the historical 320 MB ceiling.
        private const val DISK_LOW = 80 * MB
        private const val DISK_MID = 160 * MB
        private const val DISK_HIGH = 320 * MB

        internal fun memoryCacheMaxSize(): Int =
            tieredCacheSize(Runtime.getRuntime().maxMemory()).toInt()

        internal fun diskCacheMaxSize(): Int =
            tieredDiskCacheSize(Runtime.getRuntime().maxMemory()).toInt()

        /**
         * Returns the image memory cache size for the given per-app heap limit.
         *
         * Tiers:
         * - `maxMemoryBytes < 512MB` → 8 MB
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

        /**
         * Returns the image disk cache size for the given per-app heap limit.
         *
         * We use heap as a proxy for overall device capability — devices with
         * tiny per-app heaps almost always have limited storage too.
         *
         * Tiers:
         * - `maxMemoryBytes < 512MB` → 80 MB
         * - `maxMemoryBytes < 1 GB`  → 160 MB
         * - `maxMemoryBytes >= 1 GB` → 320 MB
         */
        internal fun tieredDiskCacheSize(maxMemoryBytes: Long): Long = when {
            maxMemoryBytes < TIER_LOW -> DISK_LOW
            maxMemoryBytes < TIER_MID -> DISK_MID
            else -> DISK_HIGH
        }
    }
}
