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

package com.lanraragi.reader.ui

import com.lanraragi.reader.ui.dialog.ListCheckBoxDialogBuilder
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.download.DownloadRequests
import com.lanraragi.reader.ui.scene.BaseScene
import com.lanraragi.reader.domain.Archive

object CommonOperations {

    @JvmStatic
    fun startDownload(activity: MainActivity, archive: Archive, forceDefault: Boolean) {
        startDownload(activity, listOf(archive), forceDefault)
    }

    /**
     * Starts a download from a browse entry through [DownloadRequests]; this
     * helper only shows the label picker when the entry asks for one, and the
     * resulting tip.
     */
    // KNOWN-ISSUE (P2): assumes Activity context matches theme; may mismatch in multi-window
    @JvmStatic
    fun startDownload(activity: MainActivity, archives: List<Archive>, forceDefault: Boolean) {
        val dm = ServiceRegistry.dataModule.downloadManager
        when (val outcome = DownloadRequests.enqueue(activity, dm, archives, forceDefault)) {
            is DownloadRequests.Outcome.Done -> activity.showTip(
                when (outcome.feedback) {
                    DownloadRequests.Feedback.ALREADY_LOCAL -> R.string.download_already_local
                    DownloadRequests.Feedback.ADDED -> R.string.added_to_download_list
                },
                BaseScene.LENGTH_SHORT,
            )
            is DownloadRequests.Outcome.PickLabel -> showLabelPicker(activity, outcome)
        }
    }

    private fun showLabelPicker(activity: MainActivity, outcome: DownloadRequests.Outcome.PickLabel) {
        val labels = outcome.labels
        val items = Array<String?>(labels.size + 1) { i ->
            if (i == 0) activity.getString(R.string.default_download_label_name) else labels[i - 1]
        }

        @Suppress("UNCHECKED_CAST")
        ListCheckBoxDialogBuilder(
            activity, items as Array<CharSequence>,
            { builder, _, position ->
                DownloadRequests.addWithPickedLabel(
                    activity,
                    ServiceRegistry.dataModule.downloadManager,
                    outcome.toAdd,
                    picked = if (position == 0) null else items[position],
                    remember = builder?.isChecked == true,
                )
                activity.showTip(R.string.added_to_download_list, BaseScene.LENGTH_SHORT)
            },
            activity.getString(R.string.remember_download_label), false
        )
            .setTitle(R.string.download)
            .show()
    }
}
