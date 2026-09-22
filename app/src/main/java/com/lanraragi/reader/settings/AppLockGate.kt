package com.lanraragi.reader.settings

import android.content.Intent

/**
 * Process-wide app-lock state: whether the lock has been passed in this
 * process since the app last came to the foreground, plus an optional
 * resume intent so the user can be put back where they were after unlock
 * (e.g. the reader on its current page).
 *
 * A process starts LOCKED. That is what closes the process-death hole: a
 * restored scene stack or an Activity launched directly by a widget,
 * shortcut or notification finds [isLocked] true and is routed through the
 * lock screen, instead of relying on the cold-start launch scene.
 *
 * LRReaderApplication wires a `ProcessLifecycleOwner` observer that calls
 * [onAppBackgrounded] from `ON_STOP`; BaseActivity / MainActivity check
 * [isLocked] from `onCreate` and `onResume`.
 *
 * Reads/writes happen on the main thread in normal use; fields are marked
 * `@Volatile` defensively.
 */
object AppLockGate {

    @Volatile
    private var unlocked: Boolean = false

    @Volatile
    private var resumeIntent: Intent? = null

    /**
     * True while an app lock is set and has not been passed since the
     * process started or the app last went to the background. With no lock
     * set the process counts as unlocked, so setting a pattern does not lock
     * the user out until the app next leaves the foreground.
     */
    fun isLocked(): Boolean {
        if (unlocked) return false
        if (!SecuritySettings.isLockEnabled()) {
            unlocked = true
            return false
        }
        return true
    }

    /** Called after the pattern (or fingerprint) was verified. */
    fun markUnlocked() {
        unlocked = true
    }

    /** Called from `ProcessLifecycleOwner.ON_STOP`. */
    fun onAppBackgrounded() {
        unlocked = false
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

    /**
     * Drop all state and count as unlocked. Called when the user clears
     * their pattern (they just proved access), and from tests.
     */
    fun reset() {
        unlocked = true
        resumeIntent = null
    }

    /** Back to the fresh-process state. Tests only. */
    internal fun resetForTesting() {
        unlocked = false
        resumeIntent = null
    }
}
