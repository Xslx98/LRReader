package com.lanraragi.reader.client.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Injects Cache-Control freshness headers on LANraragi thumbnail responses.
 *
 * LANraragi does not send Cache-Control headers on thumbnail responses, so
 * inject them here.
 * URL pattern: `{baseUrl}/api/archives/{arcid}/thumbnail` (no query params).
 * max-age=3600 → fresh for 1 h; stale-while-revalidate=82800 → serve stale
 * while revalidating for the remaining 23 h (24 h total).
 *
 * Matches on the parsed path segments, not substring containment: a page
 * request like `.../api/archives/{id}/page?path=Vol1/thumbnail.jpg` also
 * contains "/thumbnail" and would wrongly cache a multi-MB page body.
 *
 * Only successful responses are made cacheable — injecting freshness on a
 * 4xx/5xx would let OkHttp's cache pin a broken thumbnail (e.g. a reverse
 * proxy's 502 or LANraragi's 404) as fresh for an hour after the server
 * recovers.
 */
class ThumbnailCacheControlInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val resp = chain.proceed(chain.request())
        val segments = chain.request().url.pathSegments
        val isThumbnail = segments.size >= 2 &&
            segments[segments.size - 1] == "thumbnail" &&
            segments.contains("archives")
        return if (isThumbnail && resp.isSuccessful) {
            resp.newBuilder()
                .header("Cache-Control", "public, max-age=3600, stale-while-revalidate=82800")
                .removeHeader("Pragma")
                .build()
        } else {
            resp
        }
    }
}
