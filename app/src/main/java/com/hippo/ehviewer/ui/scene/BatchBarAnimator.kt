package com.hippo.ehviewer.ui.scene

import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.view.isVisible

/**
 * Slide-up/fade entrance and exit for the shared batch action card
 * (`widget_batch_action_bar.xml`), used by both list scenes. Idempotent per
 * target state: re-triggering the in-flight transition is a no-op, and each
 * call cancels the opposite one first. The hide end-action checks the marker
 * tag so a cancelled hide never leaves the bar GONE.
 */
object BatchBarAnimator {

    private const val DURATION_MS = 120L
    private const val SLIDE_DP = 32f
    private val TAG_HIDING = Any()

    fun show(bar: View) {
        if (bar.isVisible && bar.tag !== TAG_HIDING) return
        bar.animate().cancel()
        bar.tag = null
        bar.isVisible = true
        bar.alpha = 0f
        bar.translationY = SLIDE_DP * bar.resources.displayMetrics.density
        bar.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun hide(bar: View) {
        if (!bar.isVisible || bar.tag === TAG_HIDING) return
        bar.animate().cancel()
        bar.tag = TAG_HIDING
        bar.animate()
            .translationY(SLIDE_DP * bar.resources.displayMetrics.density)
            .alpha(0f)
            .setDuration(DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                if (bar.tag === TAG_HIDING) {
                    bar.tag = null
                    bar.visibility = View.GONE
                    bar.translationY = 0f
                    bar.alpha = 1f
                }
            }
            .start()
    }
}
