package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.ServiceRegistry;
import com.hippo.ehviewer.client.lrr.LRRArchiveApi;
import com.hippo.ehviewer.client.lrr.LRRAuthManager;
import com.hippo.ehviewer.client.lrr.LRRCoroutineHelper;
import com.hippo.ehviewer.client.lrr.data.LRRArchive;
import com.hippo.util.IoThreadPoolExecutor;

import okhttp3.OkHttpClient;

/**
 * Handles saving ratings to LANraragi server.
 * GET current metadata → modify rating tag → PUT back.
 */
public class RatingHelper {

    /**
     * Save rating to LANraragi server in background.
     * @param onSuccess optional callback run on background thread after successful save
     */
    public static void saveRatingToServer(@NonNull String arcid,
                                          float rating, @Nullable Runnable onSuccess) {
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                String serverUrl = LRRAuthManager.getServerUrl();
                if (serverUrl == null) return;
                OkHttpClient client = ServiceRegistry.INSTANCE.getNetworkModule().getOkHttpClient();

                // GET current metadata to get original tags
                LRRArchive archive = (LRRArchive) LRRCoroutineHelper.runSuspend(
                        (scope, cont) -> LRRArchiveApi.getArchiveMetadata(client, serverUrl, arcid, cont)
                );
                String originalTags = archive.tags != null ? archive.tags : "";

                // Remove old rating tag, add new with emoji
                String newRatingTag = "rating:" + LRRArchive.buildRatingEmoji(Math.round(rating));
                String cleaned = originalTags.replaceAll(",\\s*rating:[^,]*", "")
                        .replaceAll("rating:[^,]*\\s*,?\\s*", "")
                        .trim();
                cleaned = cleaned.replaceAll("^,\\s*|,\\s*$", "").trim();
                String updatedTags = cleaned.isEmpty() ? newRatingTag : cleaned + ", " + newRatingTag;

                // PUT back with original tags preserved
                LRRCoroutineHelper.runSuspend(
                        (scope, cont) -> LRRArchiveApi.updateArchiveMetadata(client, serverUrl, arcid, updatedTags, cont)
                );
                android.util.Log.d("RatingHelper", "Rating saved: " + newRatingTag);
                if (onSuccess != null) onSuccess.run();
            } catch (Exception e) {
                android.util.Log.e("RatingHelper", "Rating update failed", e);
            }
        });
    }
}
