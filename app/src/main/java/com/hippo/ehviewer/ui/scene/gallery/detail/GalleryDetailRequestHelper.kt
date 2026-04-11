/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.ui.scene.gallery.detail

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.data.GalleryDetail
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRCategoryApi
import com.lanraragi.reader.client.api.runSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles LANraragi metadata fetching and category-based favorite detection,
 * extracted from GalleryDetailScene to reduce its line count.
 */
class GalleryDetailRequestHelper(private val callback: Callback) {

    interface Callback {
        fun getToken(): String?
        fun getActivity(): Activity?
        fun getString(resId: Int): String
        fun onGetGalleryDetailSuccess(result: GalleryDetail)
        fun onGetGalleryDetailFailure(e: Exception)
    }

    /**
     * Fetch archive metadata from LANraragi and query categories for
     * favorite status. Calls [Callback.onGetGalleryDetailSuccess] or
     * [Callback.onGetGalleryDetailFailure] on the UI thread.
     *
     * @return true if the request was dispatched, false if prerequisites are missing
     */
    fun request(): Boolean {
        val arcid = callback.getToken()
        val serverUrl = LRRAuthManager.getServerUrl()
        if (arcid.isNullOrEmpty() || serverUrl.isNullOrEmpty()) {
            return false
        }

        val client = ServiceRegistry.networkModule.okHttpClient
        val owner = callback.getActivity() as? ComponentActivity ?: return false

        owner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val archive = runSuspend {
                    LRRArchiveApi.getArchiveMetadata(client, serverUrl, arcid)
                }
                val gd = archive.toGalleryDetail()

                // Query LANraragi categories to determine favorite status
                try {
                    val categories = runSuspend {
                        LRRCategoryApi.getCategories(client, serverUrl)
                    }
                    val matchedNames = mutableListOf<String>()
                    for (cat in categories) {
                        // Only check static categories (dynamic ones have empty archives list)
                        if (!cat.isDynamic() && cat.archives.contains(arcid)) {
                            cat.name?.let { matchedNames.add(it) }
                        }
                    }
                    if (matchedNames.isNotEmpty()) {
                        gd.isFavorited = true
                        if (matchedNames.size == 1) {
                            gd.favoriteName = matchedNames[0]
                        } else {
                            gd.favoriteName = matchedNames[0] +
                                callback.getString(R.string.lrr_category_info_suffix) +
                                matchedNames.size +
                                callback.getString(R.string.lrr_category_count_suffix)
                        }
                    }
                } catch (catEx: Exception) {
                    android.util.Log.w(
                        TAG,
                        "Failed to query categories for favorite status",
                        catEx
                    )
                    // Non-fatal: favorite status just won't show
                }

                // Cache the detail
                ServiceRegistry.dataModule.galleryDetailCache.put(gd.gid, gd)

                val act = callback.getActivity()
                act?.runOnUiThread { callback.onGetGalleryDetailSuccess(gd) }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "LRR metadata fetch failed", e)
                val act = callback.getActivity()
                act?.runOnUiThread { callback.onGetGalleryDetailFailure(e) }
            }
        }
        return true
    }

    companion object {
        private const val TAG = "GalleryDetailRequest"
    }
}
