package com.hippo.ehviewer.ui.scene.gallery.detail

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.LRRCacheKeyFactory

import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.ArchiveDetail
import com.hippo.ehviewer.ui.scene.TransitionNameFactory
import com.hippo.ehviewer.ui.widget.bindSourceServerBadge
import com.hippo.ehviewer.widget.ArchiverDownloadProgress
import com.hippo.reveal.ViewAnimationUtils
import com.hippo.util.DrawableManager
import com.hippo.widget.LoadImageView
import kotlinx.coroutines.launch

/**
 * Handles header view binding and update logic for [GalleryDetailScene].
 *
 * Owns: thumbnail loading, title display, rating, favourite state,
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
    private val sourceBadge: TextView,
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
        if (action == GalleryDetailScene.ACTION_ARCHIVE) {
            thumb.load(LRRCacheKeyFactory.getThumbKey(archive.arcid), archive.thumbnailUrl)
            title.text = archive.title
            // Archive has no uploader (LRR never populates it); clear the
            // field so a previous binding does not leak through.
            uploader.text = null
            bindSourceBadge(archive)
        }
    }

    /**
     * Show the source-server badge when the archive's source profile
     * differs from the active profile. Single-profile users (and
     * archives whose source is the active profile) see no badge — the
     * badge is intentionally not a permanent ornament; it only tags
     * cross-server items so the user understands "this comes from
     * somewhere else than the server I'm currently connected to".
     *
     * Always reads the source id from the navigation argument (the
     * persistent record from Room), never from a passed-in Archive.
     * The detail-page success path can hand us an Archive whose
     * `serverProfileId` rode in from a 1.12.7-era polluted LRU entry,
     * which would flip the badge to the active profile despite the
     * archive really belonging elsewhere.
     */
    fun bindSourceBadge(@Suppress("UNUSED_PARAMETER") archive: Archive) {
        val sid = viewModel.archive.value?.serverProfileId ?: 0L
        val activeId = LRRAuthManager.getActiveProfileId()
        val isCrossServer = sid != 0L && sid != activeId
        if (isCrossServer) {
            bindSourceServerBadge(sourceBadge, sid)
        } else {
            sourceBadge.visibility = View.GONE
        }
    }

    fun bindViewSecond(
        ad: ArchiveDetail,
        hadEagerBind: Boolean,
        context: Context?,
        inflater: android.view.LayoutInflater?,
        clickListener: View.OnClickListener,
        longClickListener: View.OnLongClickListener
    ) {
        val archive = ad.archive

        if (!hadEagerBind) {
            thumb.load(LRRCacheKeyFactory.getThumbKey(archive.arcid), archive.thumbnailUrl)
        } else if (useNetWorkLoadThumb) {
            // bindViewFirst loaded a stale thumb URL; force a network refresh.
            thumb.load(LRRCacheKeyFactory.getThumbKey(archive.arcid), archive.thumbnailUrl)
            useNetWorkLoadThumb = false
        } else {
            // Eager bind already cached the same URL — read from disk.
            thumb.load(LRRCacheKeyFactory.getThumbKey(archive.arcid), archive.thumbnailUrl, false)
        }

        title.text = archive.title
        // LRR has no uploader concept — clear the slot.
        uploader.text = null
        bindSourceBadge(archive)

        bindReadProgress(archive.progress, archive.pagecount)

        size.text = ad.size

        // LANraragi rating display
        if (archive.rating > 0) {
            ratingText.text = String.format("%.0f★", archive.rating)
            rating.rating = archive.rating
        } else {
            ratingText.text = "Not rated"
            rating.rating = 0f
        }

        updateFavoriteDrawable(archive.arcid)
        bindArchiverProgress(archive.arcid)
        if (context != null && inflater != null) {
            GalleryTagHelper.bindTags(context, inflater, tags, noTags, ad.tagGroups, clickListener, longClickListener)
        }
    }

    fun updateFavoriteDrawable(arcid: String?) {
        if (arcid.isNullOrEmpty()) return
        lifecycleOwner.lifecycleScope.launch {
            try {
                // Categories source comes from the dedicated favoriteState
                // StateFlow; the local-DB lookup ORs in for galleries that
                // are bookmarked locally but not server-side.
                val favState = viewModel.favoriteState.value
                val isFav = favState?.isFavorited == true || viewModel.isLocalFavorite(arcid)
                val name = favState?.name
                heart.post {
                    if (isFav) {
                        heart.visibility = View.VISIBLE
                        if (name == null) {
                            heart.setText(R.string.local_favorites)
                        } else {
                            heart.text = name
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

    fun bindArchiverProgress(arcid: String) {
        archiverDownloadProgress.initThread(arcid)
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
