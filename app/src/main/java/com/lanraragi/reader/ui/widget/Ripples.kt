package com.lanraragi.reader.ui.widget

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.view.View

/**
 * Bounded platform ripples in the Material ripple colours, for views whose
 * background is set in code (replaces the seven332 `Ripple` helper, which on
 * API 21+ built exactly these drawables).
 */
object Ripples {

    private const val RIPPLE_MATERIAL_DARK = 0x4DFFFFFF
    private const val RIPPLE_MATERIAL_LIGHT = 0x1F000000

    /** A ripple drawn over [content] and clipped to the view's bounds. */
    fun rippleDrawable(dark: Boolean, content: Drawable? = null): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(if (dark) RIPPLE_MATERIAL_DARK else RIPPLE_MATERIAL_LIGHT),
            content,
            ColorDrawable(Color.BLACK)
        )

    /** Wraps [view]'s background in a ripple, unless it already is one. */
    fun addRipple(view: View, dark: Boolean) {
        val background = view.background
        if (background is RippleDrawable) return
        view.background = rippleDrawable(dark, background)
    }
}
