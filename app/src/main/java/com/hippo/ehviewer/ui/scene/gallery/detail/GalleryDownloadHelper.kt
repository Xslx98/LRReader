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

import android.content.Context
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadInfoListener
import com.hippo.ehviewer.ui.CommonOperations
import com.hippo.ehviewer.ui.MainActivity

/**
 * Handles download state display, download start/delete actions, and
 * DownloadInfoListener callbacks extracted from GalleryDetailScene.
 */
class GalleryDownloadHelper(private val callback: Callback) : DownloadInfoListener {

    interface Callback {
        fun getContext(): Context?
        fun getActivity(): MainActivity?
        fun getGid(): Long
        fun getGalleryInfo(): GalleryInfo?
        fun getDownloadView(): TextView?
        fun getString(resId: Int): String
        fun getString(resId: Int, vararg formatArgs: Any): String
    }

    var downloadState: Int = DownloadInfo.STATE_INVALID
        private set

    /**
     * Initialize download state for the given gid.
     */
    fun initDownloadState(gid: Long) {
        downloadState = if (gid != -1L) {
            ServiceRegistry.dataModule.downloadManager.getDownloadState(gid)
        } else {
            DownloadInfo.STATE_INVALID
        }
    }

    /**
     * Update the download button text to reflect the current state.
     */
    fun updateDownloadText() {
        val download = callback.getDownloadView() ?: return
        when (downloadState) {
            DownloadInfo.STATE_NONE -> download.setText(R.string.download_state_none)
            DownloadInfo.STATE_WAIT -> download.setText(R.string.download_state_wait)
            DownloadInfo.STATE_DOWNLOAD -> download.setText(R.string.download_state_downloading)
            DownloadInfo.STATE_FINISH -> download.setText(R.string.download_state_downloaded)
            DownloadInfo.STATE_FAILED -> download.setText(R.string.download_state_failed)
            else -> download.setText(R.string.download)
        }
    }

    /**
     * Query the current download state and update the text if changed.
     */
    fun updateDownloadState() {
        val context = callback.getContext()
        val gid = callback.getGid()
        if (context == null || gid == -1L) {
            return
        }

        val newState = ServiceRegistry.dataModule.downloadManager.getDownloadState(gid)
        if (newState == downloadState) {
            return
        }
        downloadState = newState
        updateDownloadText()
    }

    /**
     * Handle download button click: start a new download or show delete dialog.
     */
    fun onDownload() {
        val galleryInfo = callback.getGalleryInfo() ?: return
        val activity = callback.getActivity() ?: return
        val context = callback.getContext() ?: return

        if (ServiceRegistry.dataModule.downloadManager.getDownloadState(galleryInfo.gid) == DownloadInfo.STATE_INVALID) {
            CommonOperations.startDownload(activity, galleryInfo, false)
        } else {
            AlertDialog.Builder(context)
                .setTitle(R.string.download_remove_dialog_title)
                .setMessage(
                    callback.getString(
                        R.string.download_remove_dialog_message,
                        galleryInfo.title ?: ""
                    )
                )
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    ServiceRegistry.dataModule.downloadManager.deleteDownload(
                        galleryInfo.gid
                    )
                }
                .show()
        }
    }

    // -------------------------------------------------------------------------
    // DownloadInfoListener callbacks — all just refresh the download state
    // -------------------------------------------------------------------------

    override fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
        updateDownloadState()
    }

    override fun onReplace(newInfo: DownloadInfo, oldInfo: DownloadInfo) {
    }

    override fun onUpdate(
        info: DownloadInfo,
        list: List<DownloadInfo>,
        mWaitList: List<DownloadInfo>
    ) {
        updateDownloadState()
    }

    override fun onUpdateAll() {
        updateDownloadState()
    }

    override fun onReload() {
        updateDownloadState()
    }

    override fun onChange() {
        updateDownloadState()
    }

    override fun onRemove(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
        updateDownloadState()
    }

    override fun onRenameLabel(from: String, to: String) {
    }

    override fun onUpdateLabels() {
    }
}
