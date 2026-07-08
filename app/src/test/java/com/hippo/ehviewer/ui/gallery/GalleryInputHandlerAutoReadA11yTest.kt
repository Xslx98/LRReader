package com.hippo.ehviewer.ui.gallery

import android.content.Context
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for RES-3: the reader's auto-read (auto page-turn) button is the
 * feature's only entry point, yet its icon morphs play <-> pause without ever
 * updating contentDescription, and the layout hid it from the accessibility tree
 * with importantForAccessibility="no". TalkBack users could neither discover nor
 * operate it.
 *
 * The fix keeps a state-synced contentDescription on the button so it always
 * announces the action a tap performs. This locks that behaviour into
 * [GalleryInputHandler.toggleAutoRead].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class GalleryInputHandlerAutoReadA11yTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val handler = GalleryInputHandler(object : GalleryInputHandler.Callback {
        override fun onTapMenuArea() {}
    })
    private val panel = ImageView(context)

    @Before
    fun attachPanel() {
        // getStartTransferTime() (reached via startAutoReadTimer) reads Settings
        // preferences, which must be initialised in the Robolectric sandbox.
        Settings.initialize(context)
        handler.autoTransferPanel = panel
    }

    @After
    fun releaseTimer() {
        // Enabling auto-read spins up a real ScheduledExecutorService; release it
        // so the test JVM does not leak the worker thread.
        handler.shutdown()
    }

    @Test
    fun enablingAutoRead_announcesStopAction() {
        // isAutoTransferring starts false; one toggle turns it on (pause icon),
        // so the button now stops auto-read when tapped.
        handler.toggleAutoRead(null)

        assertEquals(
            context.getString(R.string.cd_stop_auto_read),
            panel.contentDescription?.toString(),
        )
    }

    @Test
    fun disablingAutoRead_announcesStartAction() {
        handler.toggleAutoRead(null) // on
        handler.toggleAutoRead(null) // back off (play icon)

        assertEquals(
            context.getString(R.string.cd_start_auto_read),
            panel.contentDescription?.toString(),
        )
    }
}
