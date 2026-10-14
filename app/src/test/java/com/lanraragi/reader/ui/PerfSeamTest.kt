package com.lanraragi.reader.ui

import android.content.Intent
import com.lanraragi.reader.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PerfSeamTest {

    private val intent = Intent().putExtra(PerfSeam.EXTRA_SERVER_URL, "http://127.0.0.1:3939")

    @Test
    fun shippedVariantsHaveTheSeamOff() {
        // The unit-test variant is a shipped-shape variant (debug): the flag
        // must be the compile-time false that R8 strips in release.
        assertFalse(BuildConfig.PERF_SEAM)
        assertNull(PerfSeam.serverUrlFrom(intent))
    }

    @Test
    fun extraIsIgnoredWhenDisabledEvenIfPresent() {
        assertNull(PerfSeam.serverUrlFrom(intent, enabled = false))
    }

    @Test
    fun extraIsReadOnlyWhenEnabled() {
        assertEquals("http://127.0.0.1:3939", PerfSeam.serverUrlFrom(intent, enabled = true))
    }

    @Test
    fun onlyHttpUrlsAreAccepted() {
        val bad = Intent().putExtra(PerfSeam.EXTRA_SERVER_URL, "file:///etc/hosts")
        assertNull(PerfSeam.serverUrlFrom(bad, enabled = true))
        assertNull(PerfSeam.serverUrlFrom(Intent(), enabled = true))
        assertNull(PerfSeam.serverUrlFrom(null, enabled = true))
    }
}
