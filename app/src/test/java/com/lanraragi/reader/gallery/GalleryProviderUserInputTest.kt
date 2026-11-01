package com.lanraragi.reader.gallery

import com.lanraragi.framework.lib.glview.util.GalleryUtils
import com.lanraragi.reader.storage.StorageFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner's beta diagnostics (2026-11-01, downloaded archive, vertical mode):
 * on every reopen the layout reported page 0 for a moment before the saved
 * page, the provider took that for the user's navigation, cancelled the
 * start-position restore and saved page 0 — the reader always came back at
 * the top of page 1. Only an index change after a touch or key counts now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class GalleryProviderUserInputTest {

    private class Provider : GalleryProvider2() {
        override fun size(): Int = 1
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
        override fun getError(): String? = null
        override fun getImageFilename(index: Int): String = "page_$index"
        override fun save(index: Int, file: StorageFile): Boolean = false
        override fun save(index: Int, dir: StorageFile, filename: String): StorageFile? = null

        val interacted: Boolean get() = hasUserInteracted
        fun explicitPageWins(saved: Int): Boolean = openedOnOtherPage(saved)
    }

    @Before
    fun setUp() {
        GalleryUtils.setRenderThread()
    }

    @Test
    fun `an index change before any touch or key is not the user's`() {
        val provider = Provider().apply { start() }
        assertFalse(provider.interacted)
    }

    @Test
    fun `a touch or key makes later index changes the user's`() {
        val provider = Provider().apply { start() }
        provider.onUserInteraction()
        assertTrue(provider.interacted)
    }

    @Test
    fun `input from an earlier session does not carry into a new start`() {
        val provider = Provider()
        provider.onUserInteraction()
        provider.start()
        assertFalse(provider.interacted)
    }

    @Test
    fun `an explicit start page other than the saved one wins over the restore`() {
        val provider = Provider().apply { initialPageOverride = 7 }
        assertTrue(provider.explicitPageWins(saved = 3))
        assertFalse(provider.explicitPageWins(saved = 7))
        assertFalse(Provider().explicitPageWins(saved = 3))
    }
}
