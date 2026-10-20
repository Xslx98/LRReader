package com.lanraragi.reader.widget

import androidx.core.content.ContextCompat
import coil3.asImage
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.target
import coil3.size.Precision
import com.lanraragi.framework.widget.LoadImageViewNew
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry

/**
 * Loads an archive cover into [this] with Coil (2.4 P1 pilot, home list
 * only). [key] is the cache key for both tiers, as with Conaco, so a cover
 * URL that only differs by its cache-busting stamp still hits.
 *
 * Rebinding the same key while its request is in flight or done is a no-op:
 * restarting would cancel the fetch and discard its partial bytes (the
 * Conaco path's same-key policy).
 */
fun LoadImageViewNew.loadCover(key: String, url: String) {
    val current = getTag(R.id.cover_request) as? CoverRequest
    if (current != null && current.key == key && !current.failed) return
    current?.disposable?.dispose()
    val tracked = CoverRequest(key)

    val res = resources
    val placeholder = placeholder?.asImage()
    val request = ImageRequest.Builder(context)
        .data(url)
        .memoryCacheKey(key)
        .diskCacheKey(key)
        // The decode floor Conaco uses: list cells, grid tiles and the detail
        // header share one cache key, so keep it sharp for the largest one.
        .size(
            maxOf(width, res.getDimensionPixelSize(R.dimen.gallery_detail_thumb_width)),
            maxOf(height, res.getDimensionPixelSize(R.dimen.gallery_detail_thumb_height)),
        )
        .precision(Precision.INEXACT)
        .placeholder(placeholder)
        .error(ContextCompat.getDrawable(context, R.drawable.image_failed_new)?.asImage())
        .target(this)
        .listener(onError = { _, _ -> tracked.failed = true })
        .build()
    tracked.disposable = ServiceRegistry.clientModule.coverImageLoader.enqueue(request)
    setTag(R.id.cover_request, tracked)
}

/** The view's current cover request; a failed one is retried on rebind. */
private class CoverRequest(val key: String) {
    var disposable: Disposable? = null
    var failed = false
}
