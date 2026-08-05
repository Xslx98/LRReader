package com.hippo.ehviewer.client

import android.content.SharedPreferences
import java.util.concurrent.TimeUnit

/**
 * Throttles [EhTagDatabase] network update checks.
 *
 * The update check used to hit GitHub on every MainActivity creation (cold
 * start, rotation, theme toggle) with no TTL — a WAN request plus a full
 * connect-timeout burn for offline/LAN-only users on every launch.
 *
 * Policy:
 *  - at most one *successful* check per [SUCCESS_TTL_MS] (persisted, so it
 *    survives process death);
 *  - at most one *attempt* per [ATTEMPT_TTL_MS] (in-memory only, so a failed
 *    or offline attempt retries on a later cold start but not on every
 *    activity recreation within the same process).
 *
 * Timestamps in the future (device clock rolled back) are treated as expired
 * so the throttle can never wedge.
 */
class TagDbUpdateThrottle(
    private val prefs: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Volatile
    private var lastAttemptAt = 0L

    fun shouldAttempt(): Boolean {
        val now = clock()
        val lastSuccess = prefs.getLong(KEY_LAST_SUCCESS, 0L)
        if (lastSuccess != 0L && (now - lastSuccess) in 0 until SUCCESS_TTL_MS) {
            return false
        }
        val lastAttempt = lastAttemptAt
        if (lastAttempt != 0L && (now - lastAttempt) in 0 until ATTEMPT_TTL_MS) {
            return false
        }
        return true
    }

    fun recordAttempt() {
        lastAttemptAt = clock()
    }

    fun recordSuccess() {
        prefs.edit().putLong(KEY_LAST_SUCCESS, clock()).apply()
    }

    companion object {
        const val PREFS_NAME = "tag_db_update"
        private const val KEY_LAST_SUCCESS = "last_success_check"
        val SUCCESS_TTL_MS: Long = TimeUnit.HOURS.toMillis(24)
        val ATTEMPT_TTL_MS: Long = TimeUnit.MINUTES.toMillis(15)
    }
}
