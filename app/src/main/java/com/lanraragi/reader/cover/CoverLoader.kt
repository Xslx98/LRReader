package com.lanraragi.reader.cover

import android.graphics.drawable.Drawable
import android.widget.ImageView
import coil3.asDrawable
import coil3.decode.DataSource
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Precision
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry

/**
 * Archive cover and thumbnail loading for the cover image views
 * (LoadImageView / LoadImageViewNew), on the shared Coil loader
 * ([com.lanraragi.reader.module.IClientModule.coverImageLoader]). The views
 * keep drawing the result themselves (placeholder cross-fade, clip, retry),
 * so the request has no Coil target; [Callback] reports the outcome on the
 * main thread, synchronously for a memory-cache hit.
 */
object CoverLoader {

    /** Outcome of a [load], delivered on the main thread. */
    interface Callback {
        /** [fromMemory] is true for a memory-cache hit (no fade-in wanted). */
        fun onSuccess(drawable: Drawable, fromMemory: Boolean)

        fun onError()
    }

    /**
     * Starts loading [url] into [view]'s callback. [key] is the cache key of
     * both tiers, so cache-busting stamps in the URL never miss. Without
     * [useNetwork] only the caches (and [store]) are consulted. The decode
     * targets the view size floored at the detail-header thumb size: list
     * cells, grid tiles and the detail header share one key, so the cached
     * bitmap must stay sharp for the largest fixed consumer.
     */
    @JvmStatic
    fun load(
        view: ImageView,
        key: String,
        url: String,
        useNetwork: Boolean,
        hardware: Boolean,
        store: CoverStore?,
        callback: Callback,
    ): Disposable {
        val res = view.resources
        val request = ImageRequest.Builder(view.context)
            .data(if (store != null) StoredCover(url, store) else url)
            .memoryCacheKey(key)
            .diskCacheKey(key)
            .size(
                maxOf(view.width, res.getDimensionPixelSize(R.dimen.gallery_detail_thumb_width)),
                maxOf(view.height, res.getDimensionPixelSize(R.dimen.gallery_detail_thumb_height)),
            )
            .precision(Precision.INEXACT)
            .allowHardware(hardware)
            .networkCachePolicy(if (useNetwork) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .listener(
                onSuccess = { _, result ->
                    callback.onSuccess(
                        result.image.asDrawable(res),
                        result.dataSource == DataSource.MEMORY_CACHE,
                    )
                },
                onError = { _, _ -> callback.onError() },
            )
            .build()
        return ServiceRegistry.clientModule.coverImageLoader.enqueue(request)
    }
}
