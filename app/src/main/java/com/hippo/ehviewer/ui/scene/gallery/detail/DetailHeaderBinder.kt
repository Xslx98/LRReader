package com.hippo.ehviewer.ui.scene.gallery.detail

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.hippo.android.resource.AttrResources
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.LRRCacheKeyFactory
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryInfo
import com.lanraragi.reader.client.api.data.LRRArchive
import com.hippo.ehviewer.ui.scene.TransitionNameFactory
import com.hippo.ehviewer.widget.ArchiverDownloadProgress
import com.hippo.reveal.ViewAnimationUtils
import com.hippo.util.DrawableManager
import com.hippo.widget.LoadImageView
import kotlinx.coroutines.launch

/**
 * Handles header view binding and update logic for [GalleryDetailScene].
 *
 * Owns: thumbnail loading, title/uploader display, rating, favourite state,
 * archiver progress, read progress, transition names, circular reveal.
 */
internal class DetailHeaderBinder(
    private val viewModel: GalleryDetailViewModel,
    private val lifecycleOwner: LifecycleOwner,
    private val thumb: LoadImageView,
    private val title: TextView,
    private val uploader: TextView,
    private val pages: TextView,
    private val size: TextView,
    private val ratingText: TextView,
    private val rating: RatingBar,
    private val heart: TextView,
    private val heartOutline: TextView,
    private val archiverDownloadProgress: ArchiverDownloadProgress,
    private val colorBg: View,
    private val tags: LinearLayout,
    private val noTags: TextView,
) {

    var useNetWorkLoadThumb: Boolean = false

    fun setActionDrawable(text: TextView, drawable: Drawable) {
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        text.setCompoundDrawables(null, drawable, null, null)
    }

    fun ensureActionDrawable(context: Context) {
        val heartDrawable = DrawableManager.getVectorDrawable(context, R.drawable.v_heart_primary_x48)
        if (heartDrawable != null) {
            setActionDrawable(heart, heartDrawable)
        }
        val heartOutlineDrawable = DrawableManager.getVectorDrawable(context, R.drawable.v_heart_outline_primary_x48)
        if (heartOutlineDrawable != null) {
            setActionDrawable(heartOutline, heartOutlineDrawable)
        }
    }

    fun createCircularReveal(): Boolean {
        val w = colorBg.width
        val h = colorBg.height
        if (ViewCompat.isAttachedToWindow(colorBg) && w != 0 && h != 0) {
            val resources = colorBg.context.resources
            val keylineMargin = resources.getDimensionPixelSize(R.dimen.keyline_margin)
            val thumbWidth = resources.getDimensionPixelSize(R.dimen.gallery_detail_thumb_width)
            val thumbHeight = resources.getDimensionPixelSize(R.dimen.gallery_detail_thumb_height)

            val x = thumbWidth / 2 + keylineMargin
            val y = thumbHeight / 2 + keylineMargin

            val radiusX = maxOf(Math.abs(x), Math.abs(w - x))
            val radiusY = maxOf(Math.abs(y), Math.abs(h - y))
            val radius = Math.hypot(radiusX.toDouble(), radiusY.toDouble()).toFloat()

            ViewAnimationUtils.createCircularReveal(colorBg, x, y, 0f, radius)
                .setDuration(300).start()
            return true
        } else {
            return false
        }
    }

    /**
     * Creates the circular reveal, posting to the next frame if the view
     * is not yet laid out.
     */
    fun createCircularRevealOrPost(): Boolean {
        return if (!createCircularReveal()) {
            colorBg.post { createCircularReveal() }
            false
        } else {
            true
        }
    }

    fun bindViewFirst(action: String?, galleryInfo: GalleryInfo?) {
        if (galleryInfo == null) return
        if (action == GalleryDetailScene.ACTION_GALLERY_INFO ||
            action == GalleryDetailScene.ACTION_DOWNLOAD_GALLERY_INFO
        ) {
            thumb.load(LRRCacheKeyFactory.getThumbKey(galleryInfo.gid), galleryInfo.thumb)
            title.text = EhUtils.getSuitableTitle(galleryInfo)
            uploader.text = galleryInfo.uploader
        }
    }

    fun bindViewSecond(
        gd: GalleryDetail,
        galleryInfo: GalleryInfo?,
        context: Context?,
        inflater: android.view.LayoutInflater?,
        clickListener: View.OnClickListener,
        longClickListener: View.OnLongClickListener
    ) {
        if (galleryInfo == null) {
            thumb.load(LRRCacheKeyFactory.getThumbKey(gd.gid), gd.thumb)
        } else {
            if (useNetWorkLoadThumb) {
                thumb.load(LRRCacheKeyFactory.getThumbKey(gd.gid), gd.thumb)
                useNetWorkLoadThumb = false
            } else {
                thumb.load(LRRCacheKeyFactory.getThumbKey(gd.gid), gd.thumb, false)
            }
        }

        title.text = EhUtils.getSuitableTitle(gd)
        uploader.text = gd.uploader

        val info = galleryInfo ?: gd
        bindReadProgress(info)

        size.text = gd.size

        // LANraragi rating display
        if (gd.rating > 0) {
            ratingText.text = String.format("%.0f\u2605", gd.rating)
            rating.rating = gd.rating
        } else {
            ratingText.text = "Not rated"
            rating.rating = 0f
        }

        updateFavoriteDrawable(gd)
        bindArchiverProgress(gd)
        if (context != null && inflater != null) {
            GalleryTagHelper.bindTags(context, inflater, tags, noTags, gd.tags, clickListener, longClickListener)
        }
    }

    fun updateFavoriteDrawable(gd: GalleryDetail?) {
        if (gd == null) return
        lifecycleOwner.lifecycleScope.launch {
            try {
                val isFav = gd.isFavorited || viewModel.isLocalFavorite(gd.gid)
                heart.post {
                    if (isFav) {
                        heart.visibility = View.VISIBLE
                        if (gd.favoriteName == null) {
                            heart.setText(R.string.local_favorites)
                        } else {
                            heart.text = gd.favoriteName
                        }
                        heartOutline.visibility = View.GONE
                    } else {
                        heart.visibility = View.GONE
                        heartOutline.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update favorite drawable", e)
            }
        }
    }

    fun bindArchiverProgress(gd: GalleryDetail) {
        archiverDownloadProgress.initThread(gd)
    }

    fun bindReadProgress(info: GalleryInfo?) {
        if (info == null) return
        val displayProgress = if (info.progress > 0) info.progress else 1
        pages.text = "${displayProgress}/${info.pages}P"
    }

    fun setTransitionName(gid: Long) {
        if (gid != -1L) {
            ViewCompat.setTransitionName(thumb, TransitionNameFactory.getThumbTransitionName(gid))
            ViewCompat.setTransitionName(title, TransitionNameFactory.getTitleTransitionName(gid))
            ViewCompat.setTransitionName(uploader, TransitionNameFactory.getUploaderTransitionName(gid))
        }
    }

    companion object {
        private const val TAG = "DetailHeaderBinder"
    }
}
