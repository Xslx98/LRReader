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

import android.graphics.Point
import android.view.Gravity
import android.view.ViewTreeObserver
import com.github.amlcurran.showcaseview.ShowcaseView
import com.github.amlcurran.showcaseview.SimpleShowcaseEventListener
import com.github.amlcurran.showcaseview.targets.PointTarget
import com.github.amlcurran.showcaseview.targets.ViewTarget
import com.hippo.ehviewer.R
import com.hippo.ehviewer.settings.GuideSettings
import com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter
import com.hippo.ehviewer.widget.MyEasyRecyclerView
import com.hippo.lib.yorozuya.ViewUtils
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager

/**
 * Manages showcase/tutorial guide overlays for the downloads screen.
 *
 * Extracted from [DownloadsScene] to reduce its line count.
 * The three guide steps are: download thumb -> download labels.
 */
internal class DownloadGuideHelper(
    private val scene: DownloadsScene
) {

    /** Currently displayed showcase overlay, if any. */
    var showcaseView: ShowcaseView? = null
        private set

    /**
     * Kicks off the guide sequence. If the thumb guide is pending and the
     * RecyclerView is available, waits for layout; otherwise falls through
     * to [guideDownloadLabels].
     */
    fun guide(
        recyclerView: MyEasyRecyclerView?,
        layoutManager: AutoStaggeredGridLayoutManager?
    ) {
        if (GuideSettings.getGuideDownloadThumb() && recyclerView != null) {
            recyclerView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (GuideSettings.getGuideDownloadThumb()) {
                        guideDownloadThumb(recyclerView, layoutManager)
                    }
                    if (recyclerView != null) {
                        ViewUtils.removeOnGlobalLayoutListener(recyclerView.viewTreeObserver, this)
                    }
                }
            })
        } else {
            guideDownloadLabels()
        }
    }

    /**
     * Shows the "download thumb" showcase pointing at the first visible
     * item's thumbnail. Falls through to [guideDownloadLabels] on
     * completion or if preconditions are not met.
     */
    @Suppress("DEPRECATION")
    fun guideDownloadThumb(
        recyclerView: MyEasyRecyclerView?,
        layoutManager: AutoStaggeredGridLayoutManager?
    ) {
        val activity = scene.activity2
        if (activity == null || !GuideSettings.getGuideDownloadThumb() || layoutManager == null || recyclerView == null) {
            guideDownloadLabels()
            return
        }
        val position = layoutManager.findFirstCompletelyVisibleItemPositions(null)[0]
        if (position < 0) {
            guideDownloadLabels()
            return
        }
        val holder = recyclerView.findViewHolderForAdapterPosition(position)
        if (holder == null) {
            guideDownloadLabels()
            return
        }

        showcaseView = ShowcaseView.Builder(activity)
            .withMaterialShowcase()
            .setStyle(R.style.Guide)
            .setTarget(ViewTarget((holder as DownloadAdapter.DownloadHolder).thumb))
            .blockAllTouches()
            .setContentTitle(R.string.guide_download_thumb_title)
            .setContentText(R.string.guide_download_thumb_text)
            .replaceEndButton(R.layout.button_guide)
            .setShowcaseEventListener(object : SimpleShowcaseEventListener() {
                override fun onShowcaseViewDidHide(showcaseView: ShowcaseView) {
                    this@DownloadGuideHelper.showcaseView = null
                    ViewUtils.removeFromParent(showcaseView)
                    GuideSettings.putGuideDownloadThumb(false)
                    guideDownloadLabels()
                }
            }).build()
    }

    /**
     * Shows the "download labels" showcase pointing at the upper-third
     * of the screen. Opens the right drawer on dismissal.
     */
    @Suppress("DEPRECATION")
    fun guideDownloadLabels() {
        val activity = scene.activity2
        if (activity == null || !GuideSettings.getGuideDownloadLabels()) {
            return
        }

        val display = activity.windowManager.defaultDisplay
        val point = Point()
        display.getSize(point)

        showcaseView = ShowcaseView.Builder(activity)
            .withMaterialShowcase()
            .setStyle(R.style.Guide)
            .setTarget(PointTarget(point.x, point.y / 3))
            .blockAllTouches()
            .setContentTitle(R.string.guide_download_labels_title)
            .setContentText(R.string.guide_download_labels_text)
            .replaceEndButton(R.layout.button_guide)
            .setShowcaseEventListener(object : SimpleShowcaseEventListener() {
                override fun onShowcaseViewDidHide(showcaseView: ShowcaseView) {
                    this@DownloadGuideHelper.showcaseView = null
                    ViewUtils.removeFromParent(showcaseView)
                    GuideSettings.putGuideDownloadLabels(false)
                    scene.openDrawer(Gravity.RIGHT)
                }
            }).build()
    }

    /**
     * Removes the showcase view from the hierarchy if one is showing.
     * Call from [DownloadsScene.onDestroyView].
     */
    fun cleanup() {
        if (showcaseView != null) {
            ViewUtils.removeFromParent(showcaseView)
            showcaseView = null
        }
    }
}
