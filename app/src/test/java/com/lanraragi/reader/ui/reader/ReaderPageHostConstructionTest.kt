package com.lanraragi.reader.ui.reader

import android.app.Activity
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GalleryActivity builds its ReaderPageHost in a field initializer, when the
 * activity has no base context yet. A host that touched the context there
 * (SharedPreferences for the spread pairing) crashed every reader launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ReaderPageHostConstructionTest {

    @Test
    fun `the host is built before the activity has a context`() {
        val host = ReaderPageHost(Activity())

        assertFalse(host.isSpreadShown)
    }
}
