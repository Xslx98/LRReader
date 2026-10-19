package com.lanraragi.framework.lib.glview.anim

import android.content.Context
import android.provider.Settings
import kotlin.math.roundToLong

/**
 * The system animator duration scale (developer options, or 0 under
 * "Remove animations") applied to the GL reader's own animations, which do
 * not run through ValueAnimator and so never saw it. At 0 they finish on
 * their first frame; 0.5x / 2x scale them proportionally.
 *
 * [refresh] reads the setting on the main thread; [scale] only reads the
 * cached factor, so the GL render thread can call it.
 */
object AnimatorDurationScale {

    @Volatile
    private var factor = 1f

    @JvmStatic
    fun refresh(context: Context) {
        factor = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        )
    }

    @JvmStatic
    fun scale(duration: Long): Long = scale(duration, factor)

    @JvmStatic
    fun scale(duration: Long, factor: Float): Long =
        (duration * factor.coerceAtLeast(0f)).roundToLong()
}
