package com.lanraragi.reader.ui.scene.gallery.detail

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.RatingBar
import android.widget.TextView
import android.widget.Toast
import com.lanraragi.framework.lib.yorozuya.ViewUtils
import com.lanraragi.reader.R
import com.lanraragi.reader.client.api.friendlyError
import com.lanraragi.reader.domain.buildRatingEmoji

/**
 * The detail page's interactive rating bar: live emoji preview while
 * dragging, the integer rating submitted on release, and the snap-back
 * after a rolled-back write.
 */
class DetailRatingController(private val viewModel: GalleryDetailViewModel) {

    /**
     * Make rating bar interactive. LANraragi only supports integer
     * ratings (1-5), but we keep stepSize=0.5 for smooth drag feedback.
     * The value is ceiled to an integer on touch release.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun bind(rating: RatingBar, ratingText: TextView) {
        rating.setIsIndicator(false)
        rating.stepSize = 0.5f
        rating.onRatingBarChangeListener =
            RatingBar.OnRatingBarChangeListener { _, ratingValue, fromUser ->
                if (!fromUser || viewModel.archiveDetail.value == null) return@OnRatingBarChangeListener
                // Live preview: show the emoji for the rounded value while dragging
                ratingText.text = buildRatingEmoji(
                    kotlin.math.ceil(ratingValue).toInt()
                )
            }
        rating.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP
                || event.action == MotionEvent.ACTION_CANCEL
            ) {
                // Defer the read to the next frame. dispatchTouchEvent
                // calls OnTouchListener BEFORE View.onTouchEvent, so
                // reading rating.rating synchronously here snapshots the
                // pre-touch value on a *tap* gesture (DOWN→UP with no
                // MOVE in between). Drag still appears to work because
                // MOVE events let RatingBar.onTouchEvent update the
                // progress en route. post() runs after RatingBar's own
                // onTouchEvent has applied the UP coordinates.
                rating.post {
                    if (viewModel.archiveDetail.value == null) return@post
                    // Ceil to integer: 0.5→1, 1.5→2, 4.5→5, etc.
                    val finalRating = kotlin.math.ceil(rating.rating).coerceIn(0f, 5f)
                    rating.rating = finalRating
                    ratingText.text = if (finalRating > 0f) {
                        buildRatingEmoji(finalRating.toInt())
                    } else {
                        "Not rated"
                    }
                    // Single business entry point: the ViewModel does the
                    // optimistic write across every SSOT and runs the PUT.
                    // The Scene only renders.
                    viewModel.submitRating(finalRating)
                }
            }
            false // Don't consume — let RatingBar handle the touch
        }
    }

    /**
     * After a rollback the optimistic write to currentRating /
     * archiveDetail has been reverted on the IO thread; reflect the
     * restored value on the rating bar and toast the error so the
     * user knows their change did not stick.
     */
    fun onRatingError(mainView: View?, context: Context?, e: Exception) {
        rebindFromViewModel(mainView)
        if (context != null) {
            Toast.makeText(
                context,
                friendlyError(context, e),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * Snap the rating bar + emoji label back to the ViewModel's current
     * rating. Used after an in-flight PUT was rolled back (the
     * optimistic write reverted on a background thread, the UI element
     * needs to follow).
     */
    private fun rebindFromViewModel(mainView: View?) {
        mainView ?: return
        val ratingBar = ViewUtils.`$$`(mainView, R.id.rating) as? RatingBar
        val ratingTextView = ViewUtils.`$$`(mainView, R.id.rating_text) as? TextView
        val current = viewModel.currentRating.value ?: 0f
        if (ratingBar != null) ratingBar.rating = current
        if (ratingTextView != null) {
            // Match DetailHeaderBinder.bindViewSecond's "Not rated"
            // fallback verbatim so a rollback does not cause the label to
            // flip styles versus the load path.
            ratingTextView.text = if (current > 0f) {
                buildRatingEmoji(current.toInt())
            } else {
                "Not rated"
            }
        }
    }
}
