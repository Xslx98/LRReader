package com.hippo.ehviewer.client

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Contract for [TagDbUpdateThrottle]: the tag-translation update check used
 * to hit GitHub on EVERY MainActivity creation (cold start, rotation, theme
 * toggle) with no TTL. The throttle allows at most one successful check per
 * 24 h (persisted) and one attempt per 15 min (in-memory, so failures retry
 * on a later launch but not on every recreation).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class TagDbUpdateThrottleTest {

    private lateinit var prefs: SharedPreferences
    private var now = 1_000_000_000_000L

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        prefs = ctx.getSharedPreferences("test_tag_db_throttle", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    private fun newThrottle() = TagDbUpdateThrottle(prefs) { now }

    @Test
    fun `first attempt is allowed`() {
        assertTrue(newThrottle().shouldAttempt())
    }

    @Test
    fun `attempt within 15 min of a failed attempt is suppressed`() {
        val throttle = newThrottle()
        throttle.recordAttempt()
        now += TimeUnit.MINUTES.toMillis(5)
        assertFalse(throttle.shouldAttempt())
    }

    @Test
    fun `attempt after 15 min of a failed attempt is allowed`() {
        val throttle = newThrottle()
        throttle.recordAttempt()
        now += TimeUnit.MINUTES.toMillis(16)
        assertTrue(throttle.shouldAttempt())
    }

    @Test
    fun `attempt throttle is in-memory only - new instance retries immediately`() {
        val throttle = newThrottle()
        throttle.recordAttempt()
        // Process death: a fresh instance (same prefs) has no attempt memory.
        assertTrue(newThrottle().shouldAttempt())
    }

    @Test
    fun `successful check suppresses attempts for 24h across instances`() {
        val throttle = newThrottle()
        throttle.recordAttempt()
        throttle.recordSuccess()
        now += TimeUnit.HOURS.toMillis(23)
        assertFalse(newThrottle().shouldAttempt())
    }

    @Test
    fun `attempt after 24h of last success is allowed`() {
        val throttle = newThrottle()
        throttle.recordAttempt()
        throttle.recordSuccess()
        now += TimeUnit.HOURS.toMillis(25)
        assertTrue(newThrottle().shouldAttempt())
    }

    @Test
    fun `clock rollback does not wedge the throttle`() {
        val throttle = newThrottle()
        throttle.recordAttempt()
        throttle.recordSuccess()
        // Device clock moves backwards past the recorded timestamps.
        now -= TimeUnit.DAYS.toMillis(365)
        assertTrue(newThrottle().shouldAttempt())
    }
}
