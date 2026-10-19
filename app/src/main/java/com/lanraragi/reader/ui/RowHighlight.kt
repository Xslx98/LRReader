package com.lanraragi.reader.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils

/**
 * The one "this row changed" cue: a ~12% primary-colour overlay that fades
 * out over 400 ms. No scale bounce. With animations off the animator ends at
 * once, so nothing flashes.
 */
object RowHighlight {

    private const val OVERLAY_ALPHA = 0.12f
    private const val FADE_MS = 400L
    private const val OPAQUE = 255

    fun flash(row: View) {
        if (row.width == 0 || row.height == 0) return
        val value = TypedValue()
        if (!row.context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, value, true)) return
        val primary = value.data
        val overlay = ColorDrawable(
            ColorUtils.setAlphaComponent(primary, (OPAQUE * OVERLAY_ALPHA).toInt())
        )
        overlay.setBounds(0, 0, row.width, row.height)
        row.overlay.add(overlay)
        ValueAnimator.ofInt(OPAQUE, 0).apply {
            duration = FADE_MS
            addUpdateListener { overlay.alpha = it.animatedValue as Int }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    row.overlay.remove(overlay)
                }
            })
            start()
        }
    }
}
