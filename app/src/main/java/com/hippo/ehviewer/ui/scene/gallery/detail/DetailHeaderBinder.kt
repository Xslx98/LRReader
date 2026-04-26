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
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryInfo

import com.lanraragi.reader.client.api.data.LRRArchive
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.ArchiveDetail
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

    fun bindViewFirst(action: String?, archive: Archive?) {
        if (archive == null) return
        if (action == GalleryDetailScene.ACTION_GALLERY_INFO ||
            action == GalleryDetailScene.ACTION_DOWNLOAD_GALLERY_INFO ||
            action == GalleryDetailScene.ACTION_ARCHIVE
        ) {
            thumb.load(LRRCacheKeyFactory.getThumbKey(archive.arcid), archive.thumbnailUrl)
            title.text = archive.title
            // Archive has no uploader (LRR never populates it); clear the
            // field so a previous binding does not leak through.
            uploader.text = null
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
            thumb.load(LRRCacheKeyFactory.getThumbKey(gd.arcid), gd.thumb)
        } else {
            if (useNetWorkLoadThumb) {
                thumb.load(LRRCacheKeyFactory.getThumbKey(gd.arcid), gd.thumb)
                useNetWorkLoadThumb = false
            } else {
                thumb.load(LRRCacheKeyFactory.getThumbKey(gd.arcid), gd.thumb, false)
            }
        }

        title.text = gd.title
        uploader.text = gd.uploader

        val info = galleryInfo ?: gd
        bindReadProgress(info.progress, info.pages)

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

    /**
     * Bind display fields from an [ArchiveDetail] domain model.
     * Uses Archive's native fields (arcid, title, thumbnailUrl, rating, tags)
     * without going through GalleryDetail's EhViewer legacy fields.
     */
    fun bindFromArchiveDetail(
        ad: ArchiveDetail,
        context: Context?,
        inflater: android.view.LayoutInflater?,
        clickListener: View.OnClickListener,
        longClickListener: View.OnLongClickListener
    ) {
        val archive = ad.archive

        thumb.load(LRRCacheKeyFactory.getThumbKey(archive.arcid), archive.thumbnailUrl)
        title.text = archive.title
        uploader.text = null

        // Progress
        val displayProgress = if (archive.progress > 0) archive.progress else 1
        pages.text = "${displayProgress}/${archive.pagecount}P"

        size.text = ad.size ?: "N/A"

        // Rating
        if (archive.rating > 0) {
            ratingText.text = String.format("%.0f\u2605", archive.rating)
            rating.rating = archive.rating
        } else {
            ratingText.text = "Not rated"
            rating.rating = 0f
        }

        // Tags — convert domain TagGroups to GalleryTagGroup for existing tag binder
        if (context != null && inflater != null) {
            val galleryTagGroups = ad.tagGroups.map { tg ->
                val group = com.hippo.ehviewer.client.data.GalleryTagGroup()
                group.groupName = tg.namespace
                for (tag in tg.tags) {
                    group.addTag(tag)
                }
                group
            }.toTypedArray()
            GalleryTagHelper.bindTags(context, inflater, tags, noTags, galleryTagGroups, clickListener, longClickListener)
        }
    }

    fun updateFavoriteDrawable(gd: GalleryDetail?) {
        if (gd == null) return
        lifecycleOwner.lifecycleScope.launch {
            try {
                val isFav = gd.isFavorited || viewModel.isLocalFavorite(gd.arcid)
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
        archiverDownloadProgress.initThread(gd.arcid)
    }

    /**
     * Bind the "page X / total Y" display.
     *
     * @param progress 1-indexed reading progress; 0 means "unread" and is
     *   displayed as page 1
     * @param totalPages total page count for the gallery
     */
    fun bindReadProgress(progress: Int, totalPages: Int) {
        val displayProgress = if (progress > 0) progress else 1
        pages.text = "${displayProgress}/${totalPages}P"
    }

    fun setTransitionName(arcid: String?) {
        if (arcid != null) {
            ViewCompat.setTransitionName(thumb, TransitionNameFactory.getThumbTransitionName(arcid))
            ViewCompat.setTransitionName(title, TransitionNameFactory.getTitleTransitionName(arcid))
            ViewCompat.setTransitionName(uploader, TransitionNameFactory.getUploaderTransitionName(arcid))
        }
    }

    companion object {
        private const val TAG = "DetailHeaderBinder"
    }
}
