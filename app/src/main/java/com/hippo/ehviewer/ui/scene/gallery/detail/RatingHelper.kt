package com.hippo.ehviewer.ui.scene.gallery.detail

import android.util.Log
import com.hippo.ehviewer.ServiceRegistry
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.data.LRRArchive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Handles saving ratings to LANraragi server and propagating
 * the change to all local Room caches.
 */
object RatingHelper {

    private const val TAG = "RatingHelper"

    /**
     * Save rating to LANraragi server in background, then update
     * all local Room tables (DOWNLOADS, HISTORY).
     *
     * Single coroutine: local cache update first (instant Room Flow
     * propagation), then server PUT. No `runSuspend`/`runBlocking`.
     *
     * @param arcid       archive ID
     * @param rating      new rating value (1-5)
     * @param currentTags fully-qualified comma-separated tag string
     *                    (namespace:value format) from the already-loaded detail
     */
    @JvmStatic
    fun saveRatingToServer(arcid: String, rating: Float, currentTags: String) {
        ServiceRegistry.coroutineModule.ioScope.launch {
            // 1. Update local Room caches FIRST so observers see the change
            //    immediately (Room Flow invalidation → DownloadsViewModel,
            //    and HistoryScene.onResume → loadHistory)
            syncRatingToLocalCaches(arcid, rating)

            // 2. PUT to server
            try {
                val serverUrl = LRRAuthManager.getServerUrl() ?: return@launch
                val client = ServiceRegistry.networkModule.okHttpClient

                // Strip existing rating tag
                var cleaned = currentTags.replace(Regex(",\\s*rating:[^,]*"), "")
                    .replace(Regex("rating:[^,]*\\s*,?\\s*"), "")
                    .trim()
                cleaned = cleaned.replace(Regex("^,\\s*|,\\s*$"), "").trim()

                // rating <= 0 means "unrated" — just remove the tag
                val updatedTags = if (rating > 0) {
                    val newRatingTag = "rating:" + LRRArchive.buildRatingEmoji(rating.roundToInt())
                    if (cleaned.isEmpty()) newRatingTag else "$cleaned, $newRatingTag"
                } else {
                    cleaned
                }

                LRRArchiveApi.updateArchiveMetadata(client, serverUrl, arcid, updatedTags)
                Log.d(TAG, "Rating saved to server: ${if (rating > 0) "rating:${LRRArchive.buildRatingEmoji(rating.roundToInt())}" else "(removed)"} for $arcid")
            } catch (e: Exception) {
                Log.e(TAG, "Server rating update failed for $arcid", e)
            }
        }
    }

    private suspend fun syncRatingToLocalCaches(arcid: String, rating: Float) {
        // Room stores 0 for "unrated", API uses -1. Normalize.
        val dbRating = if (rating < 0) 0f else rating
        val dataModule = ServiceRegistry.dataModule
        try {
            dataModule.downloadDbRepository.updateRating(arcid, dbRating)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync rating to DOWNLOADS for $arcid", e)
        }
        try {
            dataModule.historyRepository.updateRating(arcid, dbRating)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync rating to HISTORY for $arcid", e)
        }
    }
}
