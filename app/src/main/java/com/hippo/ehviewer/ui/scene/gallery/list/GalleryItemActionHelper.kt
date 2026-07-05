package com.hippo.ehviewer.ui.scene.gallery.list

import android.view.View
import com.hippo.ehviewer.R
import com.lanraragi.reader.domain.Archive
import com.hippo.ehviewer.ui.scene.TankoubonDetailScene
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene
import com.hippo.scene.Announcer
import com.hippo.scene.SceneFragment
import com.lanraragi.reader.client.api.isTankoubonId

/**
 * Handles item click navigation for GalleryListScene.
 * Extracted to reduce GalleryListScene's line count.
 *
 * The former long-press context menu (Read / Download / Categories / Delete)
 * has been retired in favor of long-press multi-select + the batch action bar;
 * all of its actions remain reachable from the gallery detail page.
 */
class GalleryItemActionHelper(private val callback: Callback) {

    companion object {
        const val REQUEST_CODE_GALLERY_DETAIL = 100
        private const val ITEM_CLICK_DEBOUNCE_MS = 500L
    }

    interface Callback {
        fun getSceneFragment(): SceneFragment
        fun startScene(announcer: Announcer)
    }

    private var lastItemClickTime = 0L

    fun onItemClick(view: View?, gi: Archive?): Boolean {
        if (gi == null) {
            return true
        }
        // Debounce: a fast double-tap would otherwise push two identical (STANDARD launch
        // mode) detail scenes onto the stack.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastItemClickTime < ITEM_CLICK_DEBOUNCE_MS) {
            return true
        }
        lastItemClickTime = now

        // Every list open funnels through here (row click, thumb popup,
        // random FAB) — tank pseudo-entries branch to their own detail scene;
        // the archive detail path would 400 on a TANK_ id.
        if (isTankoubonId(gi.arcid)) {
            val tankArgs = android.os.Bundle()
            tankArgs.putString(TankoubonDetailScene.KEY_TANK_ID, gi.arcid)
            tankArgs.putString(TankoubonDetailScene.KEY_TANK_NAME, gi.title)
            tankArgs.putLong(TankoubonDetailScene.KEY_PROFILE_ID, gi.serverProfileId)
            callback.startScene(Announcer(TankoubonDetailScene::class.java).setArgs(tankArgs))
            return true
        }

        val args = android.os.Bundle()
        args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_ARCHIVE)
        args.putParcelable(GalleryDetailScene.KEY_ARCHIVE, gi)
        val announcer = Announcer(GalleryDetailScene::class.java).setArgs(args)
            .setRequestCode(callback.getSceneFragment(), REQUEST_CODE_GALLERY_DETAIL)
        if (view != null) {
            val thumb: View? = view.findViewById(R.id.thumb)
            if (thumb != null) {
                announcer.setTranHelper(EnterGalleryDetailTransaction(thumb))
            }
        }
        callback.startScene(announcer)
        return true
    }
}
