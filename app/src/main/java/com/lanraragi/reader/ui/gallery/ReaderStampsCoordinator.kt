package com.lanraragi.reader.ui.gallery

import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import com.lanraragi.framework.lib.glgallery.GalleryView
import com.lanraragi.reader.R
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.gallery.GalleryProvider2
import com.lanraragi.reader.gallery.TankGalleryProvider
import com.lanraragi.reader.gallery.TankSessionSeed
import com.lanraragi.reader.settings.ReadingSettings
import com.lanraragi.reader.ui.GalleryActivity
import com.lanraragi.reader.widget.StampOverlayView

/**
 * The reader's stamp overlay: picks the stamps backend for the session, owns
 * the [ReaderStampsController] and [GalleryStampOps], and keeps the overlay's
 * visibility and page transforms in step with the GL page view.
 */
class ReaderStampsCoordinator(
    private val activity: GalleryActivity,
    private val galleryView: () -> GalleryView?,
) {

    private var stamps: ReaderStampsController? = null
    private var overlay: StampOverlayView? = null
    private var ops: GalleryStampOps? = null

    /** True while the user is placing a stamp; back leaves placement first. */
    val isPlacing: Boolean
        get() = overlay?.placing == true

    /**
     * Stamp overlay read path. Requires a server-backed identity — a
     * per-archive session's archive, or a tank session's provider (global
     * pages routed per member by TankStampsBackend). The legacy local-file DIR
     * path without an archive gets no controller and the overlay stays gone.
     */
    fun setUp(
        scope: LifecycleCoroutineScope,
        archive: Archive?,
        tankSeed: TankSessionSeed?,
        provider: GalleryProvider2?,
    ) {
        overlay = activity.findViewById(R.id.stamp_overlay)
        val stampsBackend: StampsBackend? = archive?.let {
            LRRStampsBackend(it.arcid, it.serverProfileId)
        } ?: tankSeed?.let { seed ->
            (provider as? TankGalleryProvider)?.let { tankProvider ->
                TankStampsBackend(tankProvider, seed.profileId)
            }
        }
        stampsBackend?.let { backend ->
            val stamps = ReaderStampsController(
                scope = scope,
                backend = backend,
                onDataChanged = { onDataChanged() },
            )
            this.stamps = stamps
            overlay?.stampsProvider = { page0 -> stamps.stampsForDisplayPage(page0) }
            overlay?.let { overlay ->
                val ops = GalleryStampOps(activity, stamps, overlay)
                overlay.callback = ops
                this.ops = ops
            }
            refreshVisibility()
        }
    }

    fun refreshVisibility() {
        val overlay = overlay ?: return
        val stamps = stamps
        val visible = stamps != null &&
            stamps.support != ReaderStampsController.Support.UNSUPPORTED &&
            (ReadingSettings.getReaderStamps() || stamps.sessionVisible)
        // The per-frame transform collection feeds only this overlay — keep
        // it off while hidden. Enabling requests a render, so a fresh
        // snapshot follows the (possibly stale) synchronous pump below.
        galleryView()?.setPageTransformCollectionEnabled(visible)
        overlay.isVisible = visible
        if (visible) {
            pumpTransforms()
        }
    }

    private fun onDataChanged() {
        // A support probe may resolve to UNSUPPORTED while the user is mid
        // placement (e.g. the pre-0.9.8 404 lands after the menu tap). Kill
        // the mode here — the overlay would otherwise keep eating touches
        // while refreshVisibility() hides it. Fires at most once:
        // exitPlacementMode() clears `placing`.
        if (stamps?.support == ReaderStampsController.Support.UNSUPPORTED &&
            overlay?.placing == true
        ) {
            ops?.exitPlacementMode()
            Toast.makeText(activity, R.string.stamps_unsupported, Toast.LENGTH_LONG).show()
        }
        refreshVisibility()
        overlay?.invalidate()
    }

    private fun pumpTransforms() {
        val overlay = overlay ?: return
        val transforms = galleryView()?.getPageTransforms().orEmpty()
        overlay.transforms = transforms
        stamps?.ensureVisiblePagesLoaded(transforms.map { it.index })
    }

    /** The GL view moved or scaled its pages; follow only while visible. */
    fun onPageTransformsChanged() {
        if (overlay?.isVisible == true) pumpTransforms()
    }

    fun isAvailable(): Boolean = ops?.isAvailable() == true

    fun startPlacement() {
        ops?.startPlacementMode()
    }

    fun exitPlacementMode() {
        ops?.exitPlacementMode()
    }

    fun showStampedPagesDialog() {
        ops?.showStampedPagesDialog()
    }

    /** Drops the floating stamp card, whose anchor a page turn or relayout invalidates. */
    fun dismissCard() {
        ops?.dismissCard()
    }

    fun clear() {
        ops?.dismissCard()
        ops = null
        stamps = null
        overlay = null
    }
}
