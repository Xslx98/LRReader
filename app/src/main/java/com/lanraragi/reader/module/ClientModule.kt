package com.lanraragi.reader.module

import android.content.Context
import com.lanraragi.framework.conaco.Conaco
import com.lanraragi.reader.ImageBitmapHelper
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.gallery.ReaderPageCache
import com.lanraragi.reader.client.api.LRRTagCache
import com.lanraragi.reader.client.api.PageThumbnailCache
import com.lanraragi.framework.lib.image.Image
import java.io.File

/**
 * Manages client-side singletons: Conaco (image loader) and
 * ImageBitmapHelper (bitmap decoder).
 * Extracted from LRReaderApplication to reduce its responsibility scope.
 */
class ClientModule(
    private val context: Context,
    private val networkModule: INetworkModule
) : IClientModule {

    init {
        ServiceRegistry.registerCacheable(LRRTagCache)
        // LrrFileListCache is intentionally NOT registered: its entries are
        // keyed by (serverUrl, arcid) and stay valid across profile switches —
        // wiping them on switch would discard exactly the cross-profile warm
        // hits the server-keying enables. The 30-minute TTL bounds staleness.
        ServiceRegistry.registerCacheable(ReaderPageCache)
        ServiceRegistry.registerCacheable(PageThumbnailCache)
        // Memory pressure (audit C12); the reader GalleryProvider registers itself.
        com.lanraragi.reader.util.MemoryTrim.register(ReaderPageCache)
        com.lanraragi.reader.util.MemoryTrim.register(PageThumbnailCache)
    }

    override val imageBitmapHelper: ImageBitmapHelper by lazy {
        // Density-aware admission cap: the decode floor (128x192dp) in px
        // varies with density, and a fixed pixel cap sat BELOW the floor on
        // high-density devices — floor-bounded decodes silently bypassed the
        // memory cache and every rebind re-decoded from disk (audit #12).
        val res = context.resources
        ImageBitmapHelper(
            ImageBitmapHelper.cacheCapForFloor(
                res.getDimensionPixelSize(R.dimen.gallery_detail_thumb_width),
                res.getDimensionPixelSize(R.dimen.gallery_detail_thumb_height),
            )
        )
    }

    override val conaco: Conaco<Image> by lazy {
        Conaco.Builder<Image>().apply {
            hasMemoryCache = true
            memoryCacheMaxSize = memoryCacheMaxSize()
            hasDiskCache = true
            diskCacheDir = File(context.cacheDir, "thumb")
            diskCacheMaxSize = diskCacheMaxSize(context)
            okHttpClient = networkModule.thumbFetchClient
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

        private const val THUMB_RAM_FRACTION = 16
        private const val THUMB_MIN = 32 * MB
        private const val THUMB_MAX = 96 * MB
        private const val THUMB_LOW_RAM = 16 * MB

        // Disk cache sizes per tier — small devices typically also have
        // limited internal storage, so cap thumbnail cache aggressively
        // there. High-end devices keep the historical 320 MB ceiling.
        private const val DISK_LOW = 80 * MB
        private const val DISK_MID = 160 * MB
        private const val DISK_HIGH = 320 * MB

        internal fun memoryCacheMaxSize(): Int = thumbMemoryCacheSize(
            com.lanraragi.framework.lib.yorozuya.OSUtils.getTotalMemory(),
            com.lanraragi.reader.util.MemoryTrim.isLowRamDevice(),
        ).toInt()

        /**
         * Thumbnail memory cache from total RAM (audit 2026-10-04 C19 / PERF-07):
         * RAM/16 within 32..96 MB, 16 MB on low-RAM devices. Keying it on
         * Runtime.maxMemory() put nearly every device in the 32 MB tier because
         * largeHeap makes maxMemory ~512 MB everywhere; covers are hardware
         * bitmaps, so the Java heap was never the limit.
         */
        internal fun thumbMemoryCacheSize(totalMemBytes: Long, lowRam: Boolean): Long {
            if (lowRam) return THUMB_LOW_RAM
            return (totalMemBytes / THUMB_RAM_FRACTION).coerceIn(THUMB_MIN, THUMB_MAX)
        }

        internal fun diskCacheMaxSize(context: Context): Int = com.lanraragi.reader.util.CacheBudget
            .thumbs(context, tieredDiskCacheSize(Runtime.getRuntime().maxMemory())).toInt()

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
