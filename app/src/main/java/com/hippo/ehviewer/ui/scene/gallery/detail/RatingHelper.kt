package com.hippo.ehviewer.ui.scene.gallery.detail

import android.util.Log
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.download.DownloadManager
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.data.LRRArchive
import com.lanraragi.reader.client.api.runSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Handles saving ratings to LANraragi server.
 * GET current metadata -> modify rating tag -> PUT back.
 */
object RatingHelper {

    /**
     * Save rating to LANraragi server in background.
     * @param onSuccess optional callback run on background thread after successful save
     */
    @JvmStatic
    fun saveRatingToServer(arcid: String, rating: Float, onSuccess: Runnable?) {
        ServiceRegistry.coroutineModule.ioScope.launch {
            try {
                val serverUrl = LRRAuthManager.getServerUrl() ?: return@launch
                val client = ServiceRegistry.networkModule.okHttpClient

                // GET current metadata to get original tags
                val archive = runSuspend {
                    LRRArchiveApi.getArchiveMetadata(client, serverUrl, arcid)
                }
                val originalTags = archive.tags ?: ""

                // Remove old rating tag, add new with emoji
                val newRatingTag = "rating:" + LRRArchive.buildRatingEmoji(rating.roundToInt())
                var cleaned = originalTags.replace(Regex(",\\s*rating:[^,]*"), "")
                    .replace(Regex("rating:[^,]*\\s*,?\\s*"), "")
                    .trim()
                cleaned = cleaned.replace(Regex("^,\\s*|,\\s*$"), "").trim()
                val updatedTags = if (cleaned.isEmpty()) newRatingTag else "$cleaned, $newRatingTag"

                // PUT back with original tags preserved
                runSuspend {
                    LRRArchiveApi.updateArchiveMetadata(client, serverUrl, arcid, updatedTags)
                }
                Log.d("RatingHelper", "Rating saved: $newRatingTag")

                // Sync rating to local DownloadInfo so Downloads page
                // shows the updated value without a manual refresh.
                val dm = ServiceRegistry.dataModule.downloadManager
                syncRatingToDownloadInfo(dm, arcid, rating)

                onSuccess?.run()
            } catch (e: Exception) {
                Log.e("RatingHelper", "Rating update failed", e)
            }
        }
    }

    /**
     * Find the DownloadInfo whose token matches [arcid], update its
     * rating field in-memory, and persist to DB (rating is a Room
     * @ColumnInfo). Must read the list on the main thread because
     * DownloadManager collections are main-thread-only.
     */
    private suspend fun syncRatingToDownloadInfo(
        dm: DownloadManager, arcid: String, rating: Float
    ) {
        val info = withContext(Dispatchers.Main) {
            dm.allDownloadInfoList.firstOrNull { it.token == arcid }
        } ?: return
        info.rating = rating
        try {
            ServiceRegistry.dataModule.downloadDbRepository.putDownloadInfo(info)
        } catch (e: Exception) {
            Log.w("RatingHelper", "Failed to persist rating to download DB", e)
        }
    }
}
