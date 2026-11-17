package com.lanraragi.reader.reading

import com.lanraragi.framework.lib.glgallery.GalleryProvider
import com.lanraragi.framework.lib.glview.util.GalleryUtils
import com.lanraragi.reader.reading.GalleryProvider2.Lifecycle
import com.lanraragi.reader.storage.StorageFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One lifecycle value behind size(), getError() and the stopped checks of every provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class GalleryProviderLifecycleTest {

    private class Provider : GalleryProvider2() {
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
        override fun getImageFilename(index: Int): String = "page_$index"
        override fun save(index: Int, file: StorageFile): Boolean = false
        override fun save(index: Int, dir: StorageFile, filename: String): StorageFile? = null

        fun ready(count: Int) = markReady(count)
        fun fail(message: String) = markFailed(message)
        val stopped: Boolean get() = isStopped
        fun shownPage(page: Int) = onPageRead(page)
        val restoreState: RestoreState get() = restore
    }

    @Before
    fun setUp() {
        GalleryUtils.setRenderThread()
    }

    @Test
    fun `a provider waits until its page count is known`() {
        val provider = Provider()
        assertEquals(GalleryProvider.STATE_WAIT, provider.size())

        provider.start()
        assertEquals(Lifecycle.Starting, provider.lifecycle)
        assertEquals(GalleryProvider.STATE_WAIT, provider.size())

        provider.ready(12)
        assertEquals(12, provider.size())
        assertNull(provider.getError())
    }

    @Test
    fun `a page count published before start is ignored`() {
        val provider = Provider()
        provider.ready(12)
        assertEquals(Lifecycle.Idle, provider.lifecycle)
    }

    @Test
    fun `a tank's changing page count re-enters ready`() {
        val provider = Provider().apply { start() }
        provider.ready(12)
        provider.ready(10)
        assertEquals(10, provider.size())
    }

    @Test
    fun `a failure shows its message, also after the pages were known`() {
        val provider = Provider().apply { start() }
        provider.ready(12)
        provider.fail("Failed to load pages")

        assertEquals(GalleryProvider.STATE_ERROR, provider.size())
        assertEquals("Failed to load pages", provider.getError())

        provider.ready(3)
        assertEquals("an error is not undone by a late count", GalleryProvider.STATE_ERROR, provider.size())

        provider.fail("a later failure")
        assertEquals("the first failure is the cause shown", "Failed to load pages", provider.getError())
    }

    @Test
    fun `stop is terminal and keeps the size the reader last saw`() {
        val provider = Provider().apply { start() }
        provider.ready(12)
        provider.stop()

        assertTrue(provider.stopped)
        assertEquals(12, provider.size())

        provider.ready(20)
        provider.fail("late")
        assertEquals("work finishing after stop changes nothing", Lifecycle.Stopped(12), provider.lifecycle)
    }

    @Test
    fun `a failed session keeps its message while it is torn down`() {
        val provider = Provider().apply { start() }
        provider.fail("Failed to load pages")
        provider.stop()

        assertEquals(GalleryProvider.STATE_ERROR, provider.size())
        assertEquals("Failed to load pages", provider.getError())
    }

    @Test
    fun `a read page is the start page and, before a touch, not the user's navigation`() {
        val provider = Provider().apply { start() }
        provider.shownPage(4)

        assertEquals(4, provider.getStartPage())
        assertFalse(provider.restoreState.userNavigated)

        provider.onUserInteraction()
        provider.shownPage(5)
        assertTrue(provider.restoreState.userNavigated)
    }
}
