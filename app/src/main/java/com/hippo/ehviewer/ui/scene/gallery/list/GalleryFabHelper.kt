package com.hippo.ehviewer.ui.scene.gallery.list

import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.hippo.widget.FabLayout

/**
 * Handles FabLayout primary/secondary click and expand/collapse callbacks
 * for [GalleryListScene].
 *
 * Implements [FabLayout.OnClickFabListener] and [FabLayout.OnExpandListener]
 * so the Scene can register this helper directly with the FabLayout, removing
 * those interface implementations from the Scene itself.
 *
 * Uses property-provider lambdas instead of a Callback interface so the Scene
 * initialization is concise (single-expression per dependency).
 */
internal class GalleryFabHelper(
    private val stateHelper: () -> GalleryStateHelper?,
    private val contentHelper: () -> GalleryListDataHelper?,
    private val filterHelper: () -> GalleryFilterHelper?,
    private val itemActionHelper: () -> GalleryItemActionHelper?,
    private val uploadHelper: () -> GalleryUploadHelper?,
) : FabLayout.OnClickFabListener, FabLayout.OnExpandListener {

    override fun onClickPrimaryFab(view: FabLayout, fab: FloatingActionButton) {
        if ((stateHelper()?.state
                ?: GalleryStateHelper.STATE_NORMAL) == GalleryStateHelper.STATE_NORMAL
        ) {
            view.toggle()
        }
    }

    override fun onClickSecondaryFab(view: FabLayout, fab: FloatingActionButton, position: Int) {
        val helper = contentHelper() ?: return

        when (position) {
            0 -> { // Toggle multi-tag search
                filterHelper()?.toggleFilter()
            }
            1 -> { // Refresh
                helper.refresh()
            }
            2 -> { // Random
                val gInfoL = helper.data
                if (gInfoL.isNullOrEmpty()) return
                itemActionHelper()?.onItemClick(
                    null,
                    gInfoL[(Math.random() * gInfoL.size).toInt()]
                )
            }
            3 -> { // Upload archive (LRR only)
                uploadHelper()?.showUploadFilePicker()
            }
            4 -> { // URL download (LRR only)
                uploadHelper()?.showUrlDownloadDialog()
            }
        }

        view.isExpanded = false
    }

    override fun onExpand(expanded: Boolean) {
        stateHelper()?.onFabExpand(expanded)
    }
}
