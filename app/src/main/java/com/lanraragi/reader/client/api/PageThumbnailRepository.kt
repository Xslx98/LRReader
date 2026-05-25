/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lanraragi.reader.client.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.hippo.ehviewer.ServiceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-page thumbnail loader for the detail-page preview grid.
 *
 * **Responsibilities:**
 *  1. Memory cache lookup ([PageThumbnailCache]).
 *  2. In-flight de-duplication — concurrent callers for the same
 *     `(arcid, page)` share a single OkHttp call and Bitmap decode.
 *  3. Concurrency bound ([PARALLEL_FETCHES]) so a fast scroll on a
 *     1000-page manhwa does not pile dozens of simultaneous requests
 *     on the LANraragi server.
 *  4. 202 polling with exponential backoff — LANraragi answers
 *     `GET /thumbnail?page=N` with 202 while a Minion worker
 *     generates the thumbnail; this loop reissues the request on a
 *     backoff schedule until either a 200 lands or [BACKOFFS_MS]
 *     exhaust.
 *
 * **Scope:** in-flight `async` blocks live on
 * `ServiceRegistry.coroutineModule.ioScope` so a Scene leaving does
 * not cancel a thumbnail download mid-flight (its result still lands
 * in [PageThumbnailCache] and the next entry hits the cache). The
 * ViewModel-side observer is what cancels on Scene leave; the
 * underlying network work is allowed to finish cheaply.
 */
object PageThumbnailRepository {

    private const val TAG = "PageThumbRepo"

    /**
     * Max concurrent OkHttp thumbnail fetches. OkHttp's
     * `Dispatcher.maxRequestsPerHost` defaults to 5, so 8 lets us
     * keep the pipe full without overloading either client or
     * server. Above 8 the LAN-side gain saturates and we just
     * queue up at the dispatcher anyway.
     */
    private const val PARALLEL_FETCHES = 8

    /**
     * Backoff schedule (ms) between 202 retries. Sum ≈ 23s of
     * sleep, six request attempts. Suited to LANraragi's
     * synchronous Minion worker: most thumbnails generate in
     * under 2s; the long tail handles cold caches and large
     * archives.
     */
    private val BACKOFFS_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L)

    private val semaphore = Semaphore(PARALLEL_FETCHES)

    /** Deferred-keyed in-flight registry for dedup of concurrent identical requests. */
    private val inFlight = ConcurrentHashMap<String, Deferred<Result<Bitmap>>>()

    /**
     * Load the thumbnail for ([arcid], [page]). Order of operations:
     *  1. Hit [PageThumbnailCache] → return immediately.
     *  2. Hit [inFlight] → await the existing Deferred.
     *  3. Otherwise start a new fetch, register it, and return its
     *     result.
     *
     * @param baseUrl LANraragi base URL of the **source** server
     *   (resolve via [resolveSourceBaseUrl] in the caller — the
     *   active profile may not own this archive).
     */
    suspend fun loadThumbnail(arcid: String, page: Int, baseUrl: String): Result<Bitmap> {
        PageThumbnailCache.get(arcid, page)?.let { return Result.success(it) }

        val key = "$arcid|$page"
        val deferred = inFlight.computeIfAbsent(key) {
            ServiceRegistry.coroutineModule.ioScope.async {
                try {
                    // Re-check cache after acquiring slot — another
                    // caller may have completed while we waited.
                    PageThumbnailCache.get(arcid, page)?.let {
                        return@async Result.success(it)
                    }
                    fetchWithBackoff(arcid, page, baseUrl)
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        return deferred.await()
    }

    /**
     * Network fetch loop. Releases the semaphore between attempts so
     * a sleeping retry does not block other thumbnails from making
     * progress. Returns failure when the retry budget is exhausted
     * or a permanent error (4xx) occurs.
     */
    private suspend fun fetchWithBackoff(
        arcid: String,
        page: Int,
        baseUrl: String,
    ): Result<Bitmap> {
        val client = ServiceRegistry.networkModule.okHttpClient
        var lastFailure: Throwable? = null

        for (attempt in BACKOFFS_MS.indices) {
            val result = try {
                semaphore.withPermit {
                    LRRArchiveApi.fetchPageThumbnail(client, baseUrl, arcid, page)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 4xx is permanent — fast-fail without burning the rest of the budget.
                if (e is LRRHttpException && e.code in HTTP_CLIENT_ERR_START..HTTP_CLIENT_ERR_END) {
                    Log.d(TAG, "thumbnail $arcid p=$page client error ${e.code}; abort")
                    return Result.failure(e)
                }
                lastFailure = e
                Log.d(TAG, "thumbnail $arcid p=$page attempt ${attempt + 1} failed: ${e.message}")
                if (attempt < BACKOFFS_MS.lastIndex) {
                    delay(BACKOFFS_MS[attempt])
                }
                continue
            }

            when (result) {
                is LRRArchiveApi.PageThumbnailFetchResult.Ready -> {
                    val bmp = decodeBitmap(result.bytes)
                        ?: return Result.failure(IOException("Bitmap decode returned null"))
                    PageThumbnailCache.put(arcid, page, bmp)
                    return Result.success(bmp)
                }
                is LRRArchiveApi.PageThumbnailFetchResult.Pending -> {
                    if (attempt < BACKOFFS_MS.lastIndex) {
                        delay(BACKOFFS_MS[attempt])
                    }
                }
            }
        }

        return Result.failure(
            lastFailure ?: IOException("Page thumbnail not ready after ${BACKOFFS_MS.size} attempts")
        )
    }

    private suspend fun decodeBitmap(bytes: ByteArray): Bitmap? = withContext(Dispatchers.IO) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private const val HTTP_CLIENT_ERR_START = 400
    private const val HTTP_CLIENT_ERR_END = 499
}
