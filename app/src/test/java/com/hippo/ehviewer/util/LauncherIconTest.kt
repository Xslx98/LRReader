package com.hippo.ehviewer.util

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D-Icons regression lock: notification large icons must be rendered from the
 * adaptive launcher drawable, because BitmapFactory.decodeResource returns
 * null for the anydpi-v26 adaptive-icon XML once the legacy density PNGs are
 * gone. This test fails if the helper stops producing a usable bitmap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class LauncherIconTest {

    @Test
    fun largeIconBitmap_rendersAdaptiveLauncherIcon() {
        val bitmap = LauncherIcon.largeIconBitmap(
            ApplicationProvider.getApplicationContext()
        )
        assertNotNull(bitmap)
        assertTrue(bitmap!!.width > 0)
        assertTrue(bitmap.height > 0)
    }
}
