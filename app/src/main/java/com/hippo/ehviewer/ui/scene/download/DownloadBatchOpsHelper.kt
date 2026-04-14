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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadService
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter.Companion.DRAG_ENABLE
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.lib.yorozuya.collect.LongList
import com.hippo.widget.FabLayout

/**
 * Manages batch/bulk operations (start, stop, delete, move, random, drag toggle)
 * for the secondary FAB buttons. Extracted from DownloadsScene (W11-3).
 */
internal class DownloadBatchOpsHelper(private val callback: Callback) {

    interface Callback {
        val ehContext: Context?
        val activity2: Activity?
        val viewModel: DownloadsViewModel
        val mList: List<DownloadInfo>?
        val mRecyclerView: EasyRecyclerView?
        val mFabLayout: FabLayout?
        fun positionInList(position: Int): Int
        fun onClickPrimaryFab(view: FabLayout, fab: FloatingActionButton?)
        fun launchGallery(intent: Intent)
        fun getResources(): android.content.res.Resources
    }

    fun startAll(activity: Activity) {
        val intent = Intent(activity, DownloadService::class.java)
        intent.action = DownloadService.ACTION_START_ALL
        activity.startService(intent)
    }

    fun stopAll() {
        callback.viewModel.downloadManager.stopAllDownload()
    }

    fun resetReadingProgress(searching: Boolean) {
        val context = callback.ehContext ?: return
        if (searching) {
            Toast.makeText(context, R.string.download_searching, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(context)
            .setMessage(R.string.reset_reading_progress_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                callback.viewModel.downloadManager.resetAllReadingProgress()
            }.show()
    }

    /**
     * Handles a secondary FAB click at the given [position].
     * Positions: 0=CheckAll, 1=Start, 2=Stop, 3=Delete, 4=Move, 5=Random, 6=DragToggle.
     */
    fun onClickSecondaryFab(fab: FloatingActionButton, position: Int) {
        val recyclerView = callback.mRecyclerView ?: return
        val context = callback.ehContext ?: return
        val act = callback.activity2 ?: return
        val list = callback.mList ?: return

        if (position == 0) {
            recyclerView.checkAll()
            return
        }

        var gidList: LongList? = null
        var downloadInfoList: MutableList<DownloadInfo>? = null
        val collectGid = position == 1 || position == 2 || position == 3
        val collectDownloadInfo = position == 3 || position == 4
        if (collectGid) gidList = LongList()
        if (collectDownloadInfo) downloadInfoList = java.util.LinkedList()

        val stateArray = recyclerView.checkedItemPositions ?: return
        for (i in 0 until stateArray.size()) {
            if (stateArray.valueAt(i)) {
                val info = list[callback.positionInList(stateArray.keyAt(i))]
                downloadInfoList?.add(info)
                gidList?.add(info.gid)
            }
        }

        when (position) {
            1 -> { // Start
                val gids = gidList ?: return
                startRange(gids, act)
                recyclerView.outOfCustomChoiceMode()
            }
            2 -> { // Stop
                val gids = gidList ?: return
                stopRange(gids)
                recyclerView.outOfCustomChoiceMode()
            }
            3 -> { // Delete
                val gids = gidList ?: return
                val infos = downloadInfoList ?: return
                deleteRange(context, infos, gids) { deleteFiles ->
                    recyclerView.outOfCustomChoiceMode()
                    callback.viewModel.deleteRangeDownloads(infos, gids, deleteFiles)
                }
            }
            4 -> { // Move
                val infos = downloadInfoList ?: return
                moveRange(context, infos) { label ->
                    recyclerView.outOfCustomChoiceMode()
                    callback.viewModel.moveDownloads(infos, label)
                }
            }
            5 -> { // Random
                if (list.isEmpty()) return
                callback.mFabLayout?.let { callback.onClickPrimaryFab(it, null) }
                viewRandom(list)
            }
            6 -> { // Drag toggle
                setDragEnable(fab)
            }
        }
    }

    private fun startRange(gidList: LongList, activity: Activity) {
        if (gidList.isEmpty()) return
        val intent = Intent(activity, DownloadService::class.java)
        intent.action = DownloadService.ACTION_START_RANGE
        intent.putExtra(DownloadService.KEY_GID_LIST, gidList)
        activity.startService(intent)
    }

    private fun stopRange(gidList: LongList) {
        if (gidList.isEmpty()) return
        callback.viewModel.stopRangeDownloads(gidList)
    }

    private fun deleteRange(
        context: Context,
        downloadInfoList: List<DownloadInfo>,
        gidList: LongList,
        onConfirmed: (Boolean) -> Unit
    ) {
        if (downloadInfoList.isEmpty()) return
        DownloadLabelHelper.showDeleteRangeDialog(context, gidList.size()) { deleteFiles ->
            onConfirmed(deleteFiles)
        }
    }

    private fun moveRange(context: Context, downloadInfoList: List<DownloadInfo>, onLabelSelected: (String?) -> Unit) {
        if (downloadInfoList.isEmpty()) return
        DownloadLabelHelper.showMoveDialog(context) { label ->
            onLabelSelected(label)
        }
    }

    private fun viewRandom(list: List<DownloadInfo>) {
        val position = (Math.random() * list.size).toInt()
        if (position < 0 || position >= list.size) {
            return
        }
        val activity = callback.activity2 ?: return

        val intent = Intent(activity, GalleryActivity::class.java)
        intent.action = GalleryActivity.ACTION_LRR
        intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, list[position] as GalleryInfo)
        callback.launchGallery(intent)
    }

    private fun setDragEnable(fab: FloatingActionButton) {
        DRAG_ENABLE = !DRAG_ENABLE
        DownloadSettings.setDragDownloadGallery(DRAG_ENABLE)
        val context = callback.ehContext ?: return
        if (DRAG_ENABLE) {
            fab.setImageDrawable(ResourcesCompat.getDrawable(callback.getResources(), R.drawable.v_mobile_hand_left_x24, context.theme))
        } else {
            fab.setImageDrawable(ResourcesCompat.getDrawable(callback.getResources(), R.drawable.v_mobile_hand_left_off_x24, context.theme))
        }
    }
}
