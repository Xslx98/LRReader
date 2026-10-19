package com.lanraragi.reader.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The app's few haptic moments, in system feedback constants only. Plain
 * performHapticFeedback honours the system touch-feedback switch and the
 * view's own setting, so there is no in-app toggle. (Long-press into
 * multi-select needs none: the list widgets' long-click already buzzes.)
 */
object Haptics {

    /** A drag selection crossing one more item. */
    fun tick(view: View?) {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view?.performHapticFeedback(constant)
    }

    /** Something completed: a swipe past its threshold, a tank merge landing. */
    fun confirm(view: View?) {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        view?.performHapticFeedback(constant)
    }

    /** A move that cannot go further, such as a page turn past the last page. */
    fun reject(view: View?) {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view?.performHapticFeedback(constant)
    }
}
