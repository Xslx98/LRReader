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
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhRequest
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.download.DownloadService
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.settings.FavoritesSettings
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.unifile.UniFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

object CommonOperations {

    private fun doAddToFavorites(
        activity: Activity,
        galleryInfo: GalleryInfo,
        slot: Int,
        listener: EhClient.Callback<Void?>
    ) {
        if (slot == -1) {
            ServiceRegistry.coroutineModule.ioScope.launch {
                EhDB.putLocalFavoriteAsync(galleryInfo)
                withContext(Dispatchers.Main) { listener.onSuccess(null) }
            }
        } else if (slot in 0..9) {
            val client = ServiceRegistry.clientModule.ehClient
            val request = EhRequest()
            request.setMethod(EhClient.METHOD_ADD_FAVORITES)
            request.setArgs(galleryInfo.gid, galleryInfo.token, slot, "")
            request.setCallback(listener)
            client.execute(request)
        } else {
            listener.onFailure(Exception("Invalid favorite slot: $slot"))
        }
    }

    @JvmStatic
    fun addToFavorites(
        activity: Activity,
        galleryInfo: GalleryInfo,
        listener: EhClient.Callback<Void?>,
        isDefaultFavSolt: Boolean
    ) {
        val slot = FavoritesSettings.getDefaultFavSlot()
        val favCat = FavoritesSettings.getFavCat()
        val items = Array<String?>(11) { i ->
            if (i == 0) activity.getString(R.string.local_favorites) else favCat[i - 1]
        }
        if (slot in -1..9 && !isDefaultFavSolt) {
            val newFavoriteName = if (slot >= 0) items[slot + 1] else null
            doAddToFavorites(
                activity, galleryInfo, slot,
                DelegateFavoriteCallback(listener, galleryInfo, newFavoriteName, slot)
            )
        } else {
            @Suppress("UNCHECKED_CAST")
            ListCheckBoxDialogBuilder(
                activity, items as Array<CharSequence>,
                { builder, _, position ->
                    val selectedSlot = position - 1
                    val newFavoriteName = if (selectedSlot in 0..9) items[selectedSlot + 1] else null
                    doAddToFavorites(
                        activity, galleryInfo, selectedSlot,
                        DelegateFavoriteCallback(listener, galleryInfo, newFavoriteName, selectedSlot)
                    )
                    if (builder?.isChecked == true) {
                        FavoritesSettings.putDefaultFavSlot(selectedSlot)
                    } else {
                        FavoritesSettings.putDefaultFavSlot(FavoritesSettings.INVALID_DEFAULT_FAV_SLOT)
                    }
                },
                activity.getString(R.string.remember_favorite_collection), false
            )
                .setTitle(R.string.add_favorites_dialog_title)
                .setOnCancelListener { listener.onCancel() }
                .show()
        }
    }

    @JvmStatic
    fun removeFromFavorites(
        activity: Activity,
        galleryInfo: GalleryInfo,
        listener: EhClient.Callback<Void?>
    ) {
        ServiceRegistry.coroutineModule.ioScope.launch {
            EhDB.removeLocalFavoritesAsync(galleryInfo.gid)
        }
        val client = ServiceRegistry.clientModule.ehClient
        val request = EhRequest()
        request.setMethod(EhClient.METHOD_ADD_FAVORITES)
        request.setArgs(galleryInfo.gid, galleryInfo.token, -1, "")
        request.setCallback(DelegateFavoriteCallback(listener, galleryInfo, null, -2))
        client.execute(request)
    }

    private class DelegateFavoriteCallback(
        private val delegate: EhClient.Callback<Void?>,
        private val info: GalleryInfo,
        private val newFavoriteName: String?,
        private val slot: Int
    ) : EhClient.Callback<Void?> {

        override fun onSuccess(result: Void?) {
            info.favoriteName = newFavoriteName
            info.favoriteSlot = slot
            delegate.onSuccess(result)
            ServiceRegistry.dataModule.favouriteStatusRouter.modifyFavourites(info.gid, slot)
        }

        override fun onFailure(e: Exception) {
            delegate.onFailure(e)
        }

        override fun onCancel() {
            delegate.onCancel()
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
