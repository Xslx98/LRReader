package com.lanraragi.reader.widget

import android.content.Context
import android.util.TypedValue
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.cos

/**
 * The shared look of loading placeholders (cover blocks, skeleton rows): the
 * text colour at a low alpha, breathing between two tints over 1.2 s.
 */
internal object PlaceholderPulse {

    const val PERIOD_MS = 1200L

    /** Redraw interval while pulsing: a gentle fade needs no more than ~30 fps. */
    const val FRAME_MS = 33L

    private const val OPAQUE = 255

    /** Share of the text colour at the dim and bright ends of the pulse. */
    private const val MIN_TINT = 0.06f
    private const val MAX_TINT = 0.12f

    fun baseColor(context: Context): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(android.R.attr.textColorPrimary, value, true)) {
            return FALLBACK_GREY
        }
        return if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
    }

    /**
     * The fill colour [elapsedMs] into the pulse, or the dim end when not
     * [animate]; [alpha] (0..255) scales it, e.g. while a placeholder fades out.
     */
    fun color(baseColor: Int, elapsedMs: Long, animate: Boolean, alpha: Int = OPAQUE): Int {
        val level = if (animate) {
            val phase = (elapsedMs % PERIOD_MS).toDouble() / PERIOD_MS
            ((1.0 - cos(2.0 * PI * phase)) / 2.0).toFloat()
        } else {
            0f
        }
        val tint = MIN_TINT + (MAX_TINT - MIN_TINT) * level
        return ColorUtils.setAlphaComponent(baseColor, (OPAQUE * tint * alpha / OPAQUE).toInt())
    }

    private const val FALLBACK_GREY = 0xff808080.toInt()
}
