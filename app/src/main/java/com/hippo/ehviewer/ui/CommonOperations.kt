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

package com.hippo.ehviewer.ui

import android.app.Activity
import android.content.Intent
import com.hippo.app.ListCheckBoxDialogBuilder
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.download.DownloadService
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.unifile.UniFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

object CommonOperations {

    /**
     * Result callback for [addToFavorites] / [removeFromFavorites].
     *
     * The cloud-favourites slots (1-10) are gone with E-Hentai support, so the
     * callback only carries success / failure for the local-favourites table.
     */
    interface FavoriteListener {
        fun onSuccess()
        fun onFailure(e: Exception)
    }

    @JvmStatic
    fun addToFavorites(
        activity: Activity,
        galleryInfo: GalleryInfo,
        listener: FavoriteListener
    ) {
        ServiceRegistry.coroutineModule.ioScope.launch {
            try {
                EhDB.putLocalFavoriteAsync(galleryInfo)
                withContext(Dispatchers.Main) { listener.onSuccess() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { listener.onFailure(e) }
            }
        }
    }

    @JvmStatic
    fun removeFromFavorites(
        activity: Activity,
        galleryInfo: GalleryInfo,
        listener: FavoriteListener
    ) {
        ServiceRegistry.coroutineModule.ioScope.launch {
            try {
                EhDB.removeLocalFavoritesAsync(galleryInfo.gid)
                withContext(Dispatchers.Main) { listener.onSuccess() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { listener.onFailure(e) }
            }
        }
    }

    @JvmStatic
    fun startDownload(activity: MainActivity, galleryInfo: GalleryInfo, forceDefault: Boolean) {
        startDownload(activity, listOf(galleryInfo), forceDefault)
    }

    // KNOWN-ISSUE (P2): assumes Activity context matches theme; may mismatch in multi-window
    @JvmStatic
    fun startDownload(activity: MainActivity, galleryInfos: List<GalleryInfo>, forceDefault: Boolean) {
        val dm = ServiceRegistry.dataModule.downloadManager

        val toStart = com.hippo.lib.yorozuya.collect.LongList()
        val toAdd = mutableListOf<GalleryInfo>()
        for (gi in galleryInfos) {
            if (dm.containDownloadInfo(gi.gid)) {
                toStart.add(gi.gid)
            } else {
                toAdd.add(gi)
            }
        }

        if (!toStart.isEmpty()) {
            val intent = Intent(activity, DownloadService::class.java)
            intent.action = DownloadService.ACTION_START_RANGE
            intent.putExtra(DownloadService.KEY_GID_LIST, toStart)
            activity.startService(intent)
        }

        if (toAdd.isEmpty()) {
            activity.showTip(R.string.added_to_download_list, BaseScene.LENGTH_SHORT)
            return
        }

        var justStart = forceDefault
        var label: String? = null
        // Get default download label
        if (!justStart && DownloadSettings.getHasDefaultDownloadLabel()) {
            label = DownloadSettings.getDefaultDownloadLabel()
            justStart = label == null || dm.containLabel(label)
        }
        // If there is no other label, just use null label
        if (!justStart && dm.labelList.size == 0) {
            justStart = true
            label = null
        }

        if (justStart) {
            // Got default label
            for (gi in toAdd) {
                val intent = Intent(activity, DownloadService::class.java)
                intent.action = DownloadService.ACTION_START
                intent.putExtra(DownloadService.KEY_LABEL, label)
                intent.putExtra(DownloadService.KEY_GALLERY_INFO, gi)
                activity.startService(intent)
            }
            // Notify
            activity.showTip(R.string.added_to_download_list, BaseScene.LENGTH_SHORT)
        } else {
            // Let user choose label
            val list = dm.labelList
            val items = Array<String?>(list.size + 1) { i ->
                if (i == 0) activity.getString(R.string.default_download_label_name) else list[i - 1].label
            }

            @Suppress("UNCHECKED_CAST")
            ListCheckBoxDialogBuilder(
                activity, items as Array<CharSequence>,
                { builder, _, position ->
                    val selectedLabel = if (position == 0) {
                        null
                    } else {
                        val candidate = items[position]
                        if (candidate != null && dm.containLabel(candidate)) candidate else null
                    }
                    // Start download
                    for (gi in toAdd) {
                        val intent = Intent(activity, DownloadService::class.java)
                        intent.action = DownloadService.ACTION_START
                        intent.putExtra(DownloadService.KEY_LABEL, selectedLabel)
                        intent.putExtra(DownloadService.KEY_GALLERY_INFO, gi)
                        activity.startService(intent)
                    }
                    // Save settings
                    if (builder?.isChecked == true) {
                        DownloadSettings.putHasDefaultDownloadLabel(true)
                        DownloadSettings.putDefaultDownloadLabel(selectedLabel)
                    } else {
                        DownloadSettings.putHasDefaultDownloadLabel(false)
                    }
                    // Notify
                    activity.showTip(R.string.added_to_download_list, BaseScene.LENGTH_SHORT)
                },
                activity.getString(R.string.remember_download_label), false
            )
                .setTitle(R.string.download)
                .show()
        }
    }

    @JvmStatic
    fun ensureNoMediaFile(file: UniFile?) {
        if (file == null) return

        val noMedia = file.createFile(".nomedia") ?: return

        var inputStream: java.io.InputStream? = null
        try {
            inputStream = noMedia.openInputStream()
        } catch (_: IOException) {
            // Ignore
        } finally {
            IOUtils.closeQuietly(inputStream)
        }
    }

    @JvmStatic
    fun removeNoMediaFile(file: UniFile?) {
        if (file == null) return

        val noMedia = file.subFile(".nomedia")
        if (noMedia != null && noMedia.isFile) {
            noMedia.delete()
        }
    }
}
