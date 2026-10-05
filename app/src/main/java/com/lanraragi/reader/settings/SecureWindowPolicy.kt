package com.lanraragi.reader.settings

import android.os.Build

/**
 * When a window must carry `FLAG_SECURE` (audit C48 / SEC-14, ruling
 * 2026-10-04). The user's "secure mode" setting always applies. Beyond that,
 * an app lock on API < 33 needs it whenever the window may be snapshotted
 * for recents — on pause, and while the app is still locked — because
 * `setRecentsScreenshotEnabled(false)`, which keeps screenshots working,
 * only exists from API 33. Unlocked and in the foreground, screenshots stay
 * allowed.
 */
object SecureWindowPolicy {

    /**
     * @param resumedAndUnlocked true in `onResume` once the lock is passed;
     *   false in `onPause` or while the lock screen is up
     */
    fun wantsSecureFlag(
        sdkInt: Int,
        userSecureMode: Boolean,
        lockEnabled: Boolean,
        resumedAndUnlocked: Boolean,
    ): Boolean = userSecureMode ||
        (sdkInt < Build.VERSION_CODES.TIRAMISU && lockEnabled && !resumedAndUnlocked)
}
