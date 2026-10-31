package com.lanraragi.reader.ui.gallery

import android.app.Activity
import android.view.View

/**
 * Immersive-sticky system bars for fullscreen reading: [hide] while reading,
 * [show] while the reader menu is up. Laid out behind the bars in both states,
 * so showing them does not move the page.
 */
@Suppress("DEPRECATION") // The systemUiVisibility flags the reader has always used.
class ImmersiveSystemUi(activity: Activity) {

    private val decorView: View = activity.window.decorView

    fun show() {
        decorView.systemUiVisibility = SHOW_FLAGS
    }

    fun hide() {
        decorView.systemUiVisibility = HIDE_FLAGS
    }

    private companion object {
        const val LAYOUT_FLAGS = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        const val SHOW_FLAGS = View.SYSTEM_UI_FLAG_VISIBLE or LAYOUT_FLAGS
        const val HIDE_FLAGS = LAYOUT_FLAGS or
            View.SYSTEM_UI_FLAG_LOW_PROFILE or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
}
