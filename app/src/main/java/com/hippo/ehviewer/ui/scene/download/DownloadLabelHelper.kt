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
import android.content.Intent
import android.util.SparseBooleanArray
import androidx.appcompat.app.AlertDialog
import com.hippo.app.CheckBoxDialogBuilder
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.DownloadService
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.lib.yorozuya.collect.LongList
import com.hippo.unifile.UniFile
import com.hippo.util.IoThreadPoolExecutor
import java.util.LinkedList

/**
 * Handles bulk download actions (start, stop, delete, move) and label management
 * dialogs extracted from DownloadsScene to reduce its line count.
 */
class DownloadLabelHelper(private val mCallback: Callback) {

    /**
     * Callback interface so the helper can interact with its host
     * (DownloadsScene) without a direct dependency.
     */
    interface Callback {
        fun getContext(): Context?
        fun getActivity(): android.app.Activity?
        fun getString(resId: Int): String
        fun getString(resId: Int, vararg formatArgs: Any): String
        fun getDownloadManager(): DownloadManager?
        fun getList(): MutableList<DownloadInfo>?
        fun getCheckedItemPositions(): SparseBooleanArray?
        fun positionInList(position: Int): Int
        fun exitCustomChoiceMode()
        fun onClickPrimaryFabForRandom()
        fun viewRandom()
        fun setDragEnable(fab: com.google.android.material.floatingactionbutton.FloatingActionButton)
    }

    // -------------------------------------------------------------------------
    // Public API — called from DownloadsScene.onClickSecondaryFab
    // -------------------------------------------------------------------------

    /**
     * Handles the secondary FAB action at the given [position].
     * Position mapping: 0=CheckAll (handled by caller), 1=Start, 2=Stop,
     * 3=Delete, 4=Move, 5=Random, 6=DragToggle.
     */
    fun handleSecondaryFabAction(
        position: Int,
        fab: com.google.android.material.floatingactionbutton.FloatingActionButton
    ) {
        val context = mCallback.getContext() ?: return
        val activity = mCallback.getActivity() ?: return
        val list = mCallback.getList() ?: return

        var gidList: LongList? = null
        var downloadInfoList: MutableList<DownloadInfo>? = null
        val collectGid = position == 1 || position == 2 || position == 3 // Start, Stop, Delete
        val collectDownloadInfo = position == 3 || position == 4 // Delete or Move
        if (collectGid) {
            gidList = LongList()
        }
        if (collectDownloadInfo) {
            downloadInfoList = LinkedList()
        }

        val stateArray: SparseBooleanArray = mCallback.getCheckedItemPositions() ?: return
        for (i in 0 until stateArray.size()) {
            if (stateArray.valueAt(i)) {
                val info = list[mCallback.positionInList(stateArray.keyAt(i))]
                if (collectDownloadInfo) {
                    downloadInfoList!!.add(info)
                }
                if (collectGid) {
                    gidList!!.add(info.gid)
                }
            }
        }

        when (position) {
            1 -> { // Start
                if (gidList!!.isEmpty()) return
                val intent = Intent(activity, DownloadService::class.java)
                intent.action = DownloadService.ACTION_START_RANGE
                intent.putExtra(DownloadService.KEY_GID_LIST, gidList)
                activity.startService(intent)
                mCallback.exitCustomChoiceMode()
            }
            2 -> { // Stop
                if (gidList!!.isEmpty()) return
                mCallback.getDownloadManager()?.stopRangeDownload(gidList)
                mCallback.exitCustomChoiceMode()
            }
            3 -> { // Delete
                if (downloadInfoList!!.isEmpty()) return
                showDeleteRangeDialog(context, downloadInfoList, gidList!!)
            }
            4 -> { // Move
                if (downloadInfoList!!.isEmpty()) return
                showMoveDialog(context, downloadInfoList)
            }
            5 -> { // Random
                if (list.isEmpty()) return
                mCallback.onClickPrimaryFabForRandom()
                mCallback.viewRandom()
            }
            6 -> { // Drag toggle
                mCallback.setDragEnable(fab)
            }
        }
    }

    /**
     * Shows a delete confirmation dialog for a single gallery.
     */
    fun showDeleteDialog(context: Context, galleryInfo: GalleryInfo) {
        val builder = CheckBoxDialogBuilder(
            context,
            mCallback.getString(R.string.download_remove_dialog_message, galleryInfo.title ?: ""),
            mCallback.getString(R.string.download_remove_dialog_check_text),
            Settings.getRemoveImageFiles()
        )
        builder.setTitle(R.string.download_remove_dialog_title)
            .setPositiveButton(android.R.string.ok) { _, which ->
                if (which != DialogInterface.BUTTON_POSITIVE) return@setPositiveButton
                onDeleteSingle(galleryInfo, builder.isChecked)
            }
            .show()
    }

    // -------------------------------------------------------------------------
    // Internal — dialog helpers
    // -------------------------------------------------------------------------

    private fun showDeleteRangeDialog(
        context: Context,
        downloadInfoList: MutableList<DownloadInfo>,
        gidList: LongList
    ) {
        val builder = CheckBoxDialogBuilder(
            context,
            mCallback.getString(R.string.download_remove_dialog_message_2, gidList.size()),
            mCallback.getString(R.string.download_remove_dialog_check_text),
            Settings.getRemoveImageFiles()
        )
        builder.setTitle(R.string.download_remove_dialog_title)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onDeleteRange(downloadInfoList, gidList, builder.isChecked)
            }
            .show()
    }

    private fun onDeleteSingle(galleryInfo: GalleryInfo, deleteFiles: Boolean) {
        mCallback.getDownloadManager()?.deleteDownload(galleryInfo.gid)

        Settings.putRemoveImageFiles(deleteFiles)
        if (deleteFiles) {
            val gid = galleryInfo.gid
            IoThreadPoolExecutor.instance.execute {
                EhDB.removeDownloadDirname(gid)
            }
            val file = SpiderDen.getGalleryDownloadDir(galleryInfo)
            deleteFileAsync(file)
        }
    }

    private fun onDeleteRange(
        downloadInfoList: List<DownloadInfo>,
        gidList: LongList,
        deleteFiles: Boolean
    ) {
        mCallback.exitCustomChoiceMode()
        mCallback.getDownloadManager()?.deleteRangeDownload(gidList)

        Settings.putRemoveImageFiles(deleteFiles)
        if (deleteFiles) {
            val files = arrayOfNulls<UniFile>(downloadInfoList.size)
            var i = 0
            for (info in downloadInfoList) {
                val gid = info.gid
                IoThreadPoolExecutor.instance.execute {
                    EhDB.removeDownloadDirname(gid)
                }
                files[i] = SpiderDen.getGalleryDownloadDir(info)
                i++
            }
            deleteFileAsync(*files)
        }
    }

    private fun showMoveDialog(
        context: Context,
        downloadInfoList: List<DownloadInfo>
    ) {
        val labelRawList = ServiceRegistry.dataModule.downloadManager.labelList
        val labelList = ArrayList<String>(labelRawList.size + 1)
        labelList.add(mCallback.getString(R.string.default_download_label_name))
        for (i in labelRawList.indices) {
            labelRawList[i].label?.let { labelList.add(it) }
        }
        val labels = labelList.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(R.string.download_move_dialog_title)
            .setItems(labels) { _, which ->
                mCallback.exitCustomChoiceMode()
                val label = if (which == 0) null else labels[which]
                ServiceRegistry.dataModule.downloadManager.changeLabel(downloadInfoList, label)
            }
            .show()
    }

    companion object {
        private fun deleteFileAsync(vararg files: UniFile?) {
            IoThreadPoolExecutor.instance.execute {
                for (file in files) {
                    file?.delete()
                }
            }
        }
    }
}
