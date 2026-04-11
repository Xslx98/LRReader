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
import android.graphics.drawable.NinePatchDrawable
import android.util.Log
import androidx.recyclerview.widget.RecyclerView
import com.h6ah4i.android.widget.advrecyclerview.animator.DraggableItemAnimator
import com.h6ah4i.android.widget.advrecyclerview.animator.GeneralItemAnimator
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter

/**
 * Manages the [RecyclerViewDragDropManager] lifecycle for drag-and-drop
 * reordering in the downloads list.
 *
 * Extracted from [DownloadsScene] to reduce its line count. Handles
 * manager creation, shadow drawable setup, adapter wrapping, RecyclerView
 * attachment, and cleanup.
 */
internal class DownloadDragDropHelper {

    /** The underlying drag-drop manager instance. */
    var dragDropManager: RecyclerViewDragDropManager? = null
        private set

    /**
     * Creates and configures the [RecyclerViewDragDropManager], sets the
     * drag shadow drawable, and returns the wrapped adapter ready to be
     * set on the RecyclerView.
     *
     * @param context Android context for resource access
     * @param originalAdapter the [DownloadAdapter] to wrap
     * @return the wrapped adapter that supports drag-drop
     */
    fun setup(
        context: Context,
        originalAdapter: DownloadAdapter
    ): RecyclerView.Adapter<*> {
        val manager = RecyclerViewDragDropManager()
        dragDropManager = manager

        try {
            manager.setDraggingItemShadowDrawable(
                context.resources.getDrawable(R.drawable.shadow_8dp) as NinePatchDrawable
            )
        } catch (e: Exception) {
            // Ignore hardware bitmap related errors
            Log.w(TAG, "Error setting drag shadow: ${e.message}")
        }

        manager.isCheckCanDropEnabled = false
        return manager.createWrappedAdapter(originalAdapter)
    }

    /**
     * Configures the RecyclerView with a [DraggableItemAnimator] and
     * disables change animations. Also sets the drawing cache for
     * smoother drag visuals.
     */
    fun configureRecyclerView(recyclerView: RecyclerView) {
        // Set drag-aware animator
        val animator: GeneralItemAnimator = DraggableItemAnimator()
        recyclerView.itemAnimator = animator

        // Drawing cache for smoother drag visuals
        try {
            recyclerView.isDrawingCacheEnabled = true
            recyclerView.drawingCacheQuality = android.view.View.DRAWING_CACHE_QUALITY_HIGH
        } catch (e: Exception) {
            Log.w(TAG, "Error setting drawing cache: ${e.message}")
        }

        // Disable change animations
        val itemAnimator = recyclerView.itemAnimator
        if (itemAnimator is GeneralItemAnimator) {
            itemAnimator.setSupportsChangeAnimations(false)
        }
    }

    /**
     * Attaches the drag-drop manager to the given [RecyclerView].
     * Must be called after the RecyclerView's adapter and layout manager
     * are set.
     */
    fun attachToRecyclerView(recyclerView: RecyclerView) {
        val manager = dragDropManager ?: return
        try {
            manager.attachRecyclerView(recyclerView)
        } catch (e: Exception) {
            // Ignore hardware bitmap related errors
            Log.w(TAG, "Error attaching drag manager: ${e.message}")
        }
    }

    /**
     * Releases the drag-drop manager. Call from [DownloadsScene.onDestroyView].
     */
    fun cleanup() {
        dragDropManager = null
    }

    private companion object {
        private const val TAG = "DownloadDragDropHelper"
    }
}
