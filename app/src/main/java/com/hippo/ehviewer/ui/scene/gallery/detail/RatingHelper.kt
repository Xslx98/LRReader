package com.hippo.ehviewer.ui.scene.gallery.detail

import android.util.Log
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.lrr.LRRArchiveApi
import com.hippo.ehviewer.client.lrr.LRRAuthManager
import com.hippo.ehviewer.client.lrr.data.LRRArchive
import com.hippo.ehviewer.client.lrr.runSuspend
import kotlinx.coroutines.launch
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
                onSuccess?.run()
            } catch (e: Exception) {
                Log.e("RatingHelper", "Rating update failed", e)
            }
        }
    }
}
