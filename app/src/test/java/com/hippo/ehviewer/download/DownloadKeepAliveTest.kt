package com.hippo.ehviewer.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [DownloadKeepAlive] — the CPU/Wi-Fi keep-alive that stops
 * active downloads from stalling when the screen turns off.
 *
 * Regression guard for: "downloads pause when I lock the screen / leave the
 * app". The foreground service keeps the process alive but cannot keep the CPU
 * awake; this helper is what does, so verify it actually grabs and frees the
 * lock on demand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DownloadKeepAliveTest {

    private lateinit var context: Context
    private lateinit var keepAlive: DownloadKeepAlive

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keepAlive = DownloadKeepAlive(context)
    }

    @Test
    fun startsUnheld() {
        assertFalse("a fresh keep-alive must not hold any lock", keepAlive.isHeld)
    }

    @Test
    fun acquire_holdsLock() {
        keepAlive.acquire()
        assertTrue("acquire() must hold the lock so the CPU stays awake", keepAlive.isHeld)
    }

    @Test
    fun release_freesLock() {
        keepAlive.acquire()
        keepAlive.release()
        assertFalse("release() must free the lock when downloads go idle", keepAlive.isHeld)
    }

    @Test
    fun acquire_isIdempotent() {
        keepAlive.acquire()
        keepAlive.acquire()
        // Non-reference-counted: a single release must fully clear, even after
        // two acquires (a second START intent on an already-running service).
        keepAlive.release()
        assertFalse("non-reference-counted lock must clear with one release", keepAlive.isHeld)
    }

    @Test
    fun release_withoutAcquire_isSafe() {
        keepAlive.release()
        assertFalse(keepAlive.isHeld)
    }
}
