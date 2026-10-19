package com.lanraragi.reader.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock

/**
 * Stand-in for a cover or page thumbnail that has not arrived yet: a block a
 * shade off the card behind it, breathing gently ([PlaceholderPulse]). Static
 * when the system has animations off. It reschedules its own redraw only
 * while it is visible and being drawn, so off-screen placeholders cost nothing.
 */
class CoverPlaceholderDrawable(context: Context) : Drawable(), Runnable {

    private val paint = Paint()
    private val baseColor = PlaceholderPulse.baseColor(context)
    private val animate = ValueAnimator.areAnimatorsEnabled()
    private val startTime = SystemClock.uptimeMillis()
    private var drawableAlpha = OPAQUE

    override fun draw(canvas: Canvas) {
        // Fully faded out under a loaded image (TransitionDrawable keeps
        // drawing its first layer at alpha 0): stop pulsing.
        if (drawableAlpha == 0) {
            unscheduleSelf(this)
            return
        }
        val now = SystemClock.uptimeMillis()
        paint.color = PlaceholderPulse.color(baseColor, now - startTime, animate, drawableAlpha)
        canvas.drawRect(bounds, paint)
        if (animate && isVisible) {
            unscheduleSelf(this)
            scheduleSelf(this, now + PlaceholderPulse.FRAME_MS)
        }
    }

    override fun run() = invalidateSelf()

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) invalidateSelf() else unscheduleSelf(this)
        return changed
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val OPAQUE = 255
    }
}
