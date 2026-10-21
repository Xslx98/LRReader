package com.lanraragi.reader.module

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import com.lanraragi.reader.cover.StoredCoverFetcher
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.gallery.ReaderPageCache
import com.lanraragi.reader.client.api.LRRTagCache
import com.lanraragi.reader.client.api.PageThumbnailCache
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * Manages client-side singletons: the cover image loader (Coil) and the
 * client caches it registers.
 * Extracted from LRReaderApplication to reduce its responsibility scope.
 */
class ClientModule(
    private val context: Context,
    private val networkModule: INetworkModule,
    // One cover decode at a time, as the old Conaco loader did: Coil's default parallel
    // decodes left a ~12-20 MB native-heap high-water mark on the first list
    // screen (2.4 P1 measurement); covers are small, so serial decoding
    // costs no visible latency.
    private val coverDecodeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
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

    override val coverImageLoader: ImageLoader by lazy {
        // Memory and disk budgets: the RAM tiers below.
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { networkModule.thumbFetchClient }))
                add(StoredCoverFetcher.Factory())
            }
            .memoryCache {
                MemoryCache.Builder().maxSizeBytes(memoryCacheMaxSize().toLong()).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, "cover").toOkioPath())
                    .maxSizeBytes(diskCacheMaxSize(context).toLong())
                    .build()
            }
            .decoderCoroutineContext(coverDecodeDispatcher)
            .build()
    }

    override fun clearMemoryCache() {
        coverImageLoader.memoryCache?.clear()
    }

    companion object {
        private const val MB = 1024L * 1024

        // Disk tier thresholds on total RAM (/proc/meminfo MemTotal, which
        // reads a little below the nominal size: a "4 GB" phone is ~3.6 GB).
        private const val TIER_LOW = 3072 * MB     // < 3 GB RAM
        private const val TIER_MID = 6144 * MB     // < 6 GB RAM

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
            .thumbs(
                context,
                tieredDiskCacheSize(
                    com.lanraragi.framework.lib.yorozuya.OSUtils.getTotalMemory(),
                    com.lanraragi.reader.util.MemoryTrim.isLowRamDevice(),
                ),
            ).toInt()

        /**
         * Image disk cache size from total RAM (audit 2026-10-04 C12/C19,
         * PERF-15). It was keyed on Runtime.maxMemory(), which only meant
         * something with `largeHeap`; RAM is the same "device class" proxy
         * without it. The storage cache quota caps it further
         * ([com.lanraragi.reader.util.CacheBudget]).
         *
         * Tiers:
         * - low-RAM device or `< 3 GB` RAM -> 80 MB
         * - `< 6 GB` RAM -> 160 MB
         * - `>= 6 GB` RAM -> 320 MB
         */
        internal fun tieredDiskCacheSize(totalMemBytes: Long, lowRam: Boolean): Long = when {
            lowRam || totalMemBytes < TIER_LOW -> DISK_LOW
            totalMemBytes < TIER_MID -> DISK_MID
            else -> DISK_HIGH
        }
    }
}
