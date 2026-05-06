package com.hippo.ehviewer.settings

import android.content.Intent

/**
 * Process-wide flag that drives "re-prompt for the security pattern when the
 * app returns to the foreground", plus an optional resume intent so the user
 * can be put back where they were after unlock (e.g. reader auto-resume).
 *
 * EhApplication wires a `ProcessLifecycleOwner` observer that calls
 * [onAppBackgrounded] from `ON_STOP`. EhActivity / MainActivity read the flag
 * from `onResume` to decide whether to bounce the user back to SecurityScene.
 *
 * Reads/writes happen on the main thread in normal use; fields are marked
 * `@Volatile` defensively so a future caller from another thread cannot see
 * a stale value.
 */
object AppLockGate {

    @Volatile
    private var relockPending: Boolean = false

    @Volatile
    private var resumeIntent: Intent? = null

    val shouldRelock: Boolean
        get() = relockPending

    /** Called from `ProcessLifecycleOwner.ON_STOP`. Always sets the flag —
     *  consumers gate on `SecuritySettings.hasPattern()` so we don't have to
     *  touch EncryptedSharedPreferences from the lifecycle observer. */
    fun onAppBackgrounded() {
        relockPending = true
    }

    /** Atomically read + clear the relock flag. */
    fun consumeShouldRelock(): Boolean {
        val v = relockPending
        relockPending = false
        return v
    }

    /**
     * Stash an intent to re-launch after a successful unlock — e.g.
     * GalleryActivity captures its current page so the reader can be restored
     * from where the user left it. Overwrites any previous stash.
     */
    fun stashResumeIntent(intent: Intent) {
        resumeIntent = intent
    }

    /** Read + clear the resume intent. */
    fun consumeResumeIntent(): Intent? {
        val v = resumeIntent
        resumeIntent = null
        return v
    }

    /** Drop all state. Called when the user clears their pattern, and from tests. */
    fun reset() {
        relockPending = false
        resumeIntent = null
    }
}
