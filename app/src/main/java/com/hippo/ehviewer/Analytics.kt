/*
 * Analytics stub for LRReader.
 * Firebase has been removed. All analytics methods are no-ops.
 */
package com.hippo.ehviewer

import android.content.Context
import android.util.Log
import com.hippo.scene.SceneFragment

/**
 * Stub analytics — all methods are no-ops since Firebase was removed.
 */
object Analytics {
    private const val LOG_TAG = "Analytics"

    @JvmStatic
    fun start(context: Context) {
        // No-op: Firebase removed
    }

    @JvmStatic
    val isEnabled: Boolean
        get() = false

    @JvmStatic
    fun onSceneView(scene: SceneFragment) {
        // No-op
    }

    @JvmStatic
    fun recordException(e: Throwable) {
        Log.e(LOG_TAG, "Unexpected error raised", e)
    }
}
