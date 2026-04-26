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
package com.hippo.ehviewer.ui.scene.download

import android.content.Context
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import com.hippo.app.CheckBoxDialogBuilder
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.spider.SpiderDen
import com.lanraragi.reader.domain.Archive
import kotlinx.coroutines.launch

/**
 * Stateless utility for download label/bulk action dialogs.
 * All methods take explicit parameters; no Callback interface.
 */
object DownloadLabelHelper {

    /**
     * Shows a delete confirmation dialog for a single gallery.
     *
     * @param onConfirm called with (deleteFiles: Boolean) when user confirms deletion
     */
    fun showDeleteDialog(
        context: Context,
        archive: Archive,
        onConfirm: (deleteFiles: Boolean) -> Unit
    ) {
        val builder = CheckBoxDialogBuilder(
            context,
            context.getString(R.string.download_remove_dialog_message, archive.title),
            context.getString(R.string.download_remove_dialog_check_text),
            DownloadSettings.getRemoveImageFiles()
        )
        builder.setTitle(R.string.download_remove_dialog_title)
            .setPositiveButton(android.R.string.ok) { _, which ->
                if (which != DialogInterface.BUTTON_POSITIVE) return@setPositiveButton
                onConfirm(builder.isChecked)
            }
            .show()
    }

    /**
     * Shows a delete confirmation dialog for a range of downloads.
     *
     * @param onConfirm called with (deleteFiles: Boolean) when user confirms deletion
     */
    fun showDeleteRangeDialog(
        context: Context,
        count: Int,
        onConfirm: (deleteFiles: Boolean) -> Unit
    ) {
        val builder = CheckBoxDialogBuilder(
            context,
            context.getString(R.string.download_remove_dialog_message_2, count),
            context.getString(R.string.download_remove_dialog_check_text),
            DownloadSettings.getRemoveImageFiles()
        )
        builder.setTitle(R.string.download_remove_dialog_title)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onConfirm(builder.isChecked)
            }
            .show()
    }

    /**
     * Perform the actual delete for a single archive: remove the DB row +
     * in-memory entry (always), and if [deleteFiles] is true also drop the
     * `DOWNLOAD_DIRNAME` record and recursively delete the on-disk gallery
     * directory.
     *
     * The in-memory / DB removal runs synchronously on the caller's thread
     * (main-thread for [com.hippo.ehviewer.download.DownloadManager.deleteDownload]).
     * File removal is dispatched to [ServiceRegistry.coroutineModule.ioScope]
     * — don't block the caller waiting for it.
     *
     * Also persists [deleteFiles] as the remembered default for the next
     * dialog invocation.
     */
    fun performDelete(archive: Archive, deleteFiles: Boolean) {
        ServiceRegistry.dataModule.downloadManager.deleteDownload(archive.arcid)
        DownloadSettings.putRemoveImageFiles(deleteFiles)
        if (deleteFiles) {
            val arcid = archive.arcid
            val title = archive.title
            ServiceRegistry.coroutineModule.ioScope.launch {
                ServiceRegistry.dataModule.downloadDbRepository.removeDownloadDirname(arcid)
                SpiderDen.getGalleryDownloadDir(arcid, title)?.delete()
            }
        }
    }

    /**
     * Shows a move-to-label dialog.
     *
     * @param onLabelSelected called with the selected label (null = default)
     */
    fun showMoveDialog(
        context: Context,
        onLabelSelected: (label: String?) -> Unit
    ) {
        val labelRawList = ServiceRegistry.dataModule.downloadManager.labelList
        val labelList = ArrayList<String>(labelRawList.size + 1)
        labelList.add(context.getString(R.string.default_download_label_name))
        for (i in labelRawList.indices) {
            labelRawList[i].label?.let { labelList.add(it) }
        }
        val labels = labelList.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(R.string.download_move_dialog_title)
            .setItems(labels) { _, which ->
                val label = if (which == 0) null else labels[which]
                onLabelSelected(label)
            }
            .show()
    }
}
