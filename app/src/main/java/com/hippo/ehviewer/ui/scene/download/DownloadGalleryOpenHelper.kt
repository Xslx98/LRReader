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
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadState
import com.hippo.ehviewer.gallery.ReadingContext
import com.hippo.ehviewer.gallery.ReadingContextStore
import com.hippo.ehviewer.mapper.toArchive
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.ehviewer.ui.GalleryOpenHelper
import com.lanraragi.reader.domain.Archive
import com.hippo.easyrecyclerview.EasyRecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles gallery item click + read-process update after returning from
 * [GalleryActivity]. Extracted from [DownloadsScene] (W16-1).
 */
internal class DownloadGalleryOpenHelper(private val callback: Callback) {

    interface Callback {
        val ehContext: Context?
        val activity2: android.app.Activity?
        val viewModel: DownloadsViewModel
        val mList: List<DownloadInfo>?
        val mRecyclerView: EasyRecyclerView?
        val mAdapter: androidx.recyclerview.widget.RecyclerView.Adapter<*>?
        val viewLifecycleOwner: LifecycleOwner
        fun positionInList(position: Int): Int
        fun listIndexInPage(position: Int): Int
        fun launchGallery(intent: Intent)
    }

    /**
     * Handles a gallery item click. Returns true if the click was consumed.
     */
    fun onItemClick(position: Int): Boolean {
        val activity = callback.activity2 ?: return false
        val recyclerView = callback.mRecyclerView ?: return false
        val context = callback.ehContext ?: return false

        if (recyclerView.isInCustomChoice) {
            recyclerView.toggleItemChecked(position)
            return true
        }

        val list = callback.mList ?: return false
        if (position < 0 || position >= list.size) {
            return false
        }

        val downloadInfo = list[callback.positionInList(position)]

        // Use GalleryOpenHelper to prefer local files over server
        // buildReadIntent is suspend (resolves download dir from DB).
        // toArchive() zeroes pagecount, so pass the download's own state as
        // the authoritative completeness signal: only a FINISH download is a
        // complete local copy — a paused/failed/in-progress one streams.
        val archive = downloadInfo.toArchive()
        val knownComplete = downloadInfo.state == DownloadState.FINISH
        publishDownloadsContext(list, callback.positionInList(position), archive)
        callback.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val readIntent = withContext(Dispatchers.IO) {
                    GalleryOpenHelper.buildReadIntent(
                        activity, archive, knownComplete = knownComplete
                    )
                }
                callback.launchGallery(readIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build read intent", e)
            }
        }
        return true
    }

    /**
     * Snapshot the forward slice of the downloads list so the reader can offer
     * "next download". Uses the same list order the UI renders. Best-effort:
     * never blocks or breaks the read flow.
     */
    private fun publishDownloadsContext(list: List<DownloadInfo>, index: Int, anchor: Archive) {
        if (index !in list.indices || list[index].arcid != anchor.arcid) return
        val forward = list.subList(index, minOf(list.size, index + ReadingContextStore.LOCAL_WINDOW))
            .map { it.toArchive() }
        ReadingContextStore.publish(
            ReadingContext.LocalList(
                kind = ReadingContext.LocalList.Kind.DOWNLOADS,
                forwardArchives = forward,
                anchorArcid = anchor.arcid,
            )
        )
    }

    /**
     * Processes the result from [GalleryActivity]. Updates spider info cache
     * and notifies the adapter of the changed item.
     */
    fun updateReadProcess(result: ActivityResult) {
        if (result.resultCode != DownloadsScene.LOCAL_GALLERY_INFO_CHANGE) return

        val data = result.data ?: return
        @Suppress("DEPRECATION")
        val archive = data.getParcelableExtra<Archive>(GalleryActivity.EXTRA_RESULT_ARCHIVE) ?: return
        val arcid = archive.arcid

        callback.viewModel.removeSpiderInfo(arcid)
        callback.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val spiderInfo = withContext(Dispatchers.IO) {
                    SpiderInfo.getSpiderInfo(arcid)
                }
                if (spiderInfo != null) {
                    callback.viewModel.putSpiderInfo(arcid, spiderInfo)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load spider info", e)
            }
        }

        val list = callback.mList ?: return
        val adapter = callback.mAdapter ?: return
        for (i in list.indices) {
            if (list[i].arcid == arcid) {
                // Skip when the row is not on the currently-displayed page:
                // with pagination, the bare modulo mapping would land on a
                // visible row's adapter position and fully rebind the wrong
                // row (same bug class fixed in dispatchProgressChanges).
                val position = callback.viewModel.adapterPositionForListIndex(i) ?: return
                adapter.notifyItemChanged(position)
                return
            }
        }
        // Item not in the full list — nothing to notify.
    }

    companion object {
        private const val TAG = "DownloadGalleryOpenHelper"
    }
}
