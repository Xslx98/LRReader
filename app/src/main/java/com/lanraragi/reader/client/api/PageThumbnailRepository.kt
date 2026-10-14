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
import com.lanraragi.reader.ServiceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException

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
 * **Scope:** a fetch runs on `ServiceRegistry.coroutineModule.ioScope`
 * so one cancelled caller does not abort it for the others sharing it,
 * but it is reference-counted: once every caller awaiting it has been
 * cancelled (the grid scrolled past the page, the Scene left), the
 * fetch is cancelled too. Abandoned fetches would otherwise hold the
 * FIFO [semaphore] ahead of the pages now on screen and their late
 * results would evict on-screen tiles from the small
 * [PageThumbnailCache] (audit 2026-10-13 PERF-01).
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

    /** One shared fetch and the number of callers currently awaiting it. */
    private class InFlight {
        lateinit var deferred: Deferred<Result<Bitmap>>
        var waiters = 0
    }

    /** In-flight registry for dedup of concurrent identical requests; guarded by itself. */
    private val inFlight = HashMap<String, InFlight>()

    /** Number of fetches currently registered; for tests. */
    internal fun inFlightCount(): Int = synchronized(inFlight) { inFlight.size }

    /**
     * Load the thumbnail for ([arcid], [page]). Order of operations:
     *  1. Hit [PageThumbnailCache] → return immediately.
     *  2. Hit [inFlight] → await the existing fetch.
     *  3. Otherwise start a new fetch, register it, and return its
     *     result.
     *
     * Cancelling the caller cancels the fetch when no other caller is
     * still awaiting it.
     *
     * @param baseUrl LANraragi base URL of the **source** server
     *   (resolve via [resolveSourceBaseUrl] in the caller — the
     *   active profile may not own this archive).
     */
    suspend fun loadThumbnail(arcid: String, page: Int, baseUrl: String): Result<Bitmap> {
        PageThumbnailCache.get(arcid, page)?.let { return Result.success(it) }

        val key = "$arcid|$page"
        val entry = synchronized(inFlight) {
            val e = inFlight[key] ?: InFlight().also { created ->
                created.deferred = ServiceRegistry.coroutineModule.ioScope.async(start = CoroutineStart.LAZY) {
                    try {
                        // Re-check cache after acquiring slot — another
                        // caller may have completed while we waited.
                        PageThumbnailCache.get(arcid, page)?.let {
                            return@async Result.success(it)
                        }
                        fetchWithBackoff(arcid, page, baseUrl)
                    } finally {
                        synchronized(inFlight) { inFlight.remove(key, created) }
                    }
                }
                inFlight[key] = created
            }
            e.waiters++
            e
        }
        entry.deferred.start()
        try {
            return entry.deferred.await()
        } finally {
            // The last waiter leaving an unfinished fetch abandons it.
            // Unregister under the lock so no new caller can join it.
            val abandoned = synchronized(inFlight) {
                entry.waiters--
                val orphaned = entry.waiters == 0 && !entry.deferred.isCompleted
                if (orphaned) inFlight.remove(key, entry)
                orphaned
            }
            if (abandoned) entry.deferred.cancel()
        }
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

    /**
     * Decode the JPEG bytes into a grid-sized bitmap.
     *
     * Two passes:
     *  1. `inJustDecodeBounds` reads the source dimensions without
     *     allocating pixels.
     *  2. A real decode with [computeInSampleSize] downsamples any
     *     oversized server thumbnail toward the on-screen tile size, and
     *     [Bitmap.Config.RGB_565] halves the per-pixel cost (page
     *     thumbnails are opaque JPEGs — the alpha channel is dead
     *     weight).
     *
     * Both bound the per-entry memory of [PageThumbnailCache] so its
     * fixed byte budget holds the grid's full scroll working set
     * instead of evicting tiles that are still on screen — the root
     * cause of the preview grid flickering during scroll.
     */
    private suspend fun decodeBitmap(bytes: ByteArray): Bitmap? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                TARGET_DECODE_WIDTH_PX,
                TARGET_DECODE_HEIGHT_PX,
            )
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /**
     * Largest power-of-two subsample that keeps a [srcWidth] × [srcHeight]
     * source at or above the [reqWidth] × [reqHeight] target. Mirrors the
     * canonical `BitmapFactory` recipe; returns 1 (no downsample) when
     * the source already fits or when the bounds pass failed (negative /
     * zero dimensions).
     */
    @JvmStatic
    internal fun computeInSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        if (srcWidth <= 0 || srcHeight <= 0) return 1
        var inSampleSize = 1
        if (srcHeight > reqHeight || srcWidth > reqWidth) {
            val halfHeight = srcHeight / 2
            val halfWidth = srcWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private const val HTTP_CLIENT_ERR_START = 400
    private const val HTTP_CLIENT_ERR_END = 499

    /**
     * Decode-target tile size in pixels. Sized to the largest grid tile
     * (`page_thumb_target_width_large` = 160dp) at a high display density
     * so even chunky tiles on hi-DPI screens stay crisp, with the 2:3
     * page aspect for height. Sources larger than ~2× this get
     * subsampled; typical ~500px server thumbnails pass through 1:1 and
     * lean on RGB_565 alone for the memory win.
     */
    private const val TARGET_DECODE_WIDTH_PX = 480
    private const val TARGET_DECODE_HEIGHT_PX = 720
}
