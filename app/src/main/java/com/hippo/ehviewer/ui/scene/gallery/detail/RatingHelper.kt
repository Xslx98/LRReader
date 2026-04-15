package com.hippo.ehviewer.ui.scene.gallery.detail

import android.util.Log
import com.hippo.ehviewer.ServiceRegistry
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.data.LRRArchive
import com.lanraragi.reader.client.api.runSuspend
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
     * all local Room tables (DOWNLOADS, HISTORY) so that Room Flow
     * observers in DownloadsViewModel and HistoryViewModel see the
     * change immediately.
     *
     * @param arcid       archive ID
     * @param rating      new rating value (1-5)
     * @param currentTags comma-separated tag string from the already-loaded
     *                    GalleryDetail — avoids an extra GET network call
     * @param onSuccess   optional callback run on background thread after successful save
     */
    @JvmStatic
    fun saveRatingToServer(arcid: String, rating: Float, currentTags: String, onSuccess: Runnable?) {
        // Persist to local caches immediately (optimistic update).
        // Even if the server PUT fails, the next detail-page open will
        // re-fetch from server and correct the local cache.
        ServiceRegistry.coroutineModule.ioScope.launch {
            syncRatingToLocalCaches(arcid, rating)
        }

        // PUT to server (single network call — no GET needed)
        ServiceRegistry.coroutineModule.ioScope.launch {
            try {
                val serverUrl = LRRAuthManager.getServerUrl() ?: return@launch

                // Remove old rating tag, add new with emoji
                val newRatingTag = "rating:" + LRRArchive.buildRatingEmoji(rating.roundToInt())
                var cleaned = currentTags.replace(Regex(",\\s*rating:[^,]*"), "")
                    .replace(Regex("rating:[^,]*\\s*,?\\s*"), "")
                    .trim()
                cleaned = cleaned.replace(Regex("^,\\s*|,\\s*$"), "").trim()
                val updatedTags = if (cleaned.isEmpty()) newRatingTag else "$cleaned, $newRatingTag"

                val client = ServiceRegistry.networkModule.okHttpClient
                runSuspend {
                    LRRArchiveApi.updateArchiveMetadata(client, serverUrl, arcid, updatedTags)
                }
                Log.d(TAG, "Rating saved: $newRatingTag")

                onSuccess?.run()
            } catch (e: Exception) {
                Log.e(TAG, "Rating update failed", e)
            }
        }
    }

    /**
     * Update rating in DOWNLOADS and HISTORY Room tables via their
     * respective repositories. Room Flow invalidation ensures that
     * DownloadsViewModel and HistoryViewModel observers see the change.
     */
    private suspend fun syncRatingToLocalCaches(arcid: String, rating: Float) {
        val dataModule = ServiceRegistry.dataModule
        try {
            dataModule.downloadDbRepository.updateRating(arcid, rating)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync rating to DOWNLOADS for $arcid", e)
        }
        try {
            dataModule.historyRepository.updateRating(arcid, rating)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync rating to HISTORY for $arcid", e)
        }
    }
}
