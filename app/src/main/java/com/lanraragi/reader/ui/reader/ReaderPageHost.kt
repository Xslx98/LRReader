package com.lanraragi.reader.ui.reader

import android.app.Activity
import android.view.View
import com.lanraragi.framework.lib.glgallery.GalleryProvider
import com.lanraragi.framework.lib.glgallery.GalleryView
import com.lanraragi.framework.lib.glgallery.SimpleAdapter
import com.lanraragi.framework.lib.glview.anim.AnimatorDurationScale
import com.lanraragi.framework.lib.glview.image.ImageWrapper
import com.lanraragi.framework.lib.glview.view.GLRootView
import com.lanraragi.framework.lib.image.PageFit
import com.lanraragi.framework.util.ViewUtils
import com.lanraragi.reader.R
import com.lanraragi.reader.diagnostics.DiagLog
import com.lanraragi.reader.reading.GalleryProvider2
import com.lanraragi.reader.reading.SpreadPairingStore
import com.lanraragi.reader.settings.ReadingSettings
import com.lanraragi.framework.util.MemoryTrim

/**
 * The GL page stack of the reader — GLRootView, GalleryView and the adapter
 * between them and the page provider — from attach to the render-locked
 * teardown.
 */
class ReaderPageHost(private val activity: Activity) {

    var glRootView: GLRootView? = null
        private set
    var galleryView: GalleryView? = null
        private set
    private var adapter: PageAdapter? = null

    /** Scale-mode fit the cached decoded pages were sampled for (Image.readerSampleSize). */
    private var pageFit: PageFit? = null

    /** The book whose pairing is stored ([ReaderLaunch.pairingKey]); set before [attach]. */
    var pairingKey: Pair<Long, String>? = null
    // Set in attach: this host is built with the activity, before it has a context.
    private lateinit var pairingStore: SpreadPairingStore
    private var firstPageAlone = true
    private var shifted = false

    /** The cover (and every pair break) stands alone. */
    private val coverAlone: Boolean
        get() = firstPageAlone != shifted

    /** Two pages can share the screen now: the reader menu offers [shiftPairing]. */
    val isSpreadShown: Boolean
        get() = (galleryView?.currentSpreadParity ?: GalleryView.PARITY_UNKNOWN) != GalleryView.PARITY_UNKNOWN

    /**
     * Builds the page view over [provider] inside the already inflated
     * reader layout. [onDataChanged] runs on the provider's thread whenever
     * the page count may have changed.
     */
    fun attach(
        provider: GalleryProvider2,
        listener: GalleryView.Listener,
        startPage: Int,
        onGenericMotion: View.OnGenericMotionListener,
        onDataChanged: () -> Unit,
    ): GalleryView {
        val glRootView = ViewUtils.requireView(activity, R.id.gl_root_view) as GLRootView
        this.glRootView = glRootView
        val adapter = PageAdapter(glRootView, provider, onDataChanged)
        this.adapter = adapter
        pageFit = ReadingSettings.getPageFit()
        AnimatorDurationScale.refresh(activity)
        val galleryView = ReaderGalleryViews.build(activity, adapter, listener, startPage)
        this.galleryView = galleryView
        glRootView.setContentPane(galleryView)
        glRootView.setOnGenericMotionListener(onGenericMotion)
        provider.galleryView = galleryView
        provider.setListener(adapter)
        provider.setGLRoot(glRootView)
        galleryView.setSpreadMode(ReadingSettings.getSpreadReading())
        firstPageAlone = ReadingSettings.getSpreadFirstPageAlone()
        pairingStore = SpreadPairingStore.of(activity)
        val key = pairingKey
        shifted = key != null && pairingStore.isShifted(key.first, key.second)
        val parity = key?.let { pairingStore.parity(it.first, it.second, coverAlone) } ?: GalleryView.PARITY_UNKNOWN
        galleryView.setSpreadPairing(firstPageAlone, shifted, parity)
        return galleryView
    }

    /** The page-view side of a settings-dialog change. */
    fun applySettings(
        provider: GalleryProvider2?,
        layoutMode: Int, scaleMode: Int, startPosition: Int, showPageInterval: Boolean,
    ) {
        val gv = galleryView ?: return
        val pageFit = PageFit.of(layoutMode, scaleMode)
        if (pageFit != this.pageFit) {
            // Decoded pages are sampled for the old fit (a FIT-sampled wide
            // page is far too small for fit height): drop the cached ones so
            // they re-decode for the new mode. Pages on screen keep their
            // texture until they are bound again.
            this.pageFit = pageFit
            provider?.onTrimMemory(MemoryTrim.Action.CLEAR)
        }
        gv.layoutMode = layoutMode
        gv.setSpreadMode(ReadingSettings.getSpreadReading())
        val alone = ReadingSettings.getSpreadFirstPageAlone()
        if (alone != firstPageAlone) {
            firstPageAlone = alone
            repair(gv)
        }
        gv.setScaleMode(scaleMode)
        gv.setStartPosition(startPosition)

        gv.setPagerInterval(
            if (showPageInterval) {
                activity.resources.getDimensionPixelOffset(R.dimen.gallery_pager_interval)
            } else {
                0
            }
        )
        // See ReaderGalleryViews.build for the rationale: long-strip mode
        // runs with no inter-page gap regardless of the user's
        // showPageInterval choice (which governs only the pager mode's
        // discrete-page separator).
        gv.setScrollInterval(0)
    }

    /** The reader menu's "shift pairing by one": stored with the book (a tank as a whole). */
    fun shiftPairing() {
        val gv = galleryView ?: return
        shifted = !shifted
        pairingKey?.let { pairingStore.setShifted(it.first, it.second, shifted) }
        repair(gv)
    }

    /** A spread of [parity] is on screen (-1: single pages); kept for the next resume. */
    fun onSpreadShown(parity: Int) {
        pairingKey?.let { pairingStore.putParity(it.first, it.second, parity, coverAlone) }
    }

    // The cover's pairing flipped: every pair on screen moves by one page.
    // Lands with the flipped parity and stores it in the same step, since a
    // stored parity outvotes the shift (SpreadPlanner.spreadForLanding).
    private fun repair(gv: GalleryView) {
        val now = gv.currentSpreadParity
        val parity = if (now == GalleryView.PARITY_UNKNOWN) now else now xor 1
        onSpreadShown(parity)
        gv.setSpreadPairing(firstPageAlone, shifted, parity)
    }

    /**
     * Saves the intra-page scroll fraction. Vertical mode only: pager modes
     * always report 0 and would just overwrite a saved fraction with 0.
     */
    fun saveScrollFraction(provider: GalleryProvider2?) {
        val gv = galleryView
        if (provider != null && gv != null && gv.layoutMode == GalleryView.LAYOUT_TOP_TO_BOTTOM) {
            provider.putScrollFraction(gv.currentScrollFraction)
        } else {
            DiagLog.i("Progress", "fraction not saved: view=${gv != null} mode=${gv?.layoutMode}")
        }
    }

    fun onPause() {
        glRootView?.onPause()
    }

    fun onResume() {
        // The scale may have changed in system settings while we were away.
        AnimatorDurationScale.refresh(activity)
        glRootView?.onResume()
    }

    fun detach() {
        // Detach the content pane before dropping references: this is the ONLY
        // path that runs GalleryView.onDetachFromRoot → removeAllPages →
        // page unbind → ImageTexture.recycle(). Without it no reader session
        // ever tears down its pages, and a session ending on an animated page
        // (GIF/AWebP) leaks the texture's AnimateRunnable worker thread — a GC
        // root pinning the decoded page — for the process lifetime. Lock the
        // render thread so the detach cannot race an in-flight frame.
        glRootView?.let { root ->
            root.lockRenderThread()
            try {
                root.setContentPane(null)
            } finally {
                root.unlockRenderThread()
            }
        }
        glRootView = null
        galleryView = null
        adapter?.clearUploader()
        adapter = null
    }

    private inner class PageAdapter(
        glRootView: GLRootView,
        provider: GalleryProvider,
        private val dataChanged: () -> Unit,
    ) : SimpleAdapter(glRootView, provider) {

        override fun onDataChanged() {
            super.onDataChanged()
            dataChanged()
        }

        override fun onPageSucceed(index: Int, image: ImageWrapper) {
            super.onPageSucceed(index, image)
            // Setting the page image doesn't by itself flip GalleryView
            // back into a fill cycle — it just paints the new texture
            // on the next render. Pending intra-page restores in
            // ScrollLayoutManager are gated on `target.isLoaded()`,
            // so without an explicit requestFill here they'd never
            // get a second chance after the target page's image
            // arrives async.
            galleryView?.requestFill()
        }
    }
}
