package com.lanraragi.framework.util

import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher

/**
 * Back handling through [OnBackPressedDispatcher] (predictive back, targetSdk 36: an
 * overridden `Activity.onBackPressed` is no longer called).
 */
object BackDispatch {

    /**
     * Hands one back event to the next handler below [callback] (a lower callback or the
     * activity default, which finishes or moves a root task back) by stepping it aside once.
     */
    @JvmStatic
    fun passThrough(dispatcher: OnBackPressedDispatcher, callback: OnBackPressedCallback) {
        callback.isEnabled = false
        try {
            dispatcher.onBackPressed()
        } finally {
            callback.isEnabled = true
        }
    }
}
