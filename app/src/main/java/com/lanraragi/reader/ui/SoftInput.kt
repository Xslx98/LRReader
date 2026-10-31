package com.lanraragi.reader.ui

import android.app.Activity
import android.view.View
import android.view.inputmethod.InputMethodManager

/** Shows and hides the soft keyboard for scenes. */
object SoftInput {

    fun show(view: View, requestFocus: Boolean = true) {
        if (requestFocus) {
            view.requestFocus()
        }
        view.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(view, 0)
    }

    /** Hides the keyboard of the focused view of [activity], if any. */
    fun hide(activity: Activity) {
        val view = activity.currentFocus ?: return
        activity.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }
}
