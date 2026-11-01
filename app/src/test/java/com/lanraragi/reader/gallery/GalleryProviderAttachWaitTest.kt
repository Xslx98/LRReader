package com.lanraragi.reader.gallery

import com.lanraragi.framework.lib.glgallery.GalleryPageView
import com.lanraragi.framework.lib.glgallery.GalleryView
import com.lanraragi.reader.storage.StorageFile
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * GalleryActivity starts the provider before it builds the GalleryView. A
 * fast start (warm page cache, LAN server) resolved the saved page and
 * scroll fraction while galleryView was still null, skipped the jump, and
 * the reader opened at the top of the saved page: the vertical (webtoon)
 * position was lost. The start jump now waits for the view to attach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class GalleryProviderAttachWaitTest {

    private class Provider : GalleryProvider2() {
        override fun size(): Int = 1
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
        override fun getError(): String? = null
        override fun getImageFilename(index: Int): String = "page_$index"
        override fun save(index: Int, file: StorageFile): Boolean = false
        override fun save(index: Int, dir: StorageFile, filename: String): StorageFile? = null

        suspend fun startJumpTarget(timeoutMs: Long): GalleryView? = awaitGalleryView(timeoutMs)
    }

    private class Adapter : GalleryView.Adapter() {
        override fun onBind(view: GalleryPageView, index: Int) = Unit
        override fun onUnbind(view: GalleryPageView, index: Int) = Unit
        override fun getError(): String? = null
        override fun size(): Int = 1
    }

    private fun galleryView(): GalleryView =
        GalleryView.Builder(RuntimeEnvironment.getApplication(), Adapter()).build()

    @Test
    fun `a view attached after the start resolved still receives the jump`() = runBlocking {
        val provider = Provider()
        val view = galleryView()
        val target = async { provider.startJumpTarget(timeoutMs = 5_000) }
        delay(50)
        provider.galleryView = view
        assertSame(view, target.await())
    }

    @Test
    fun `an already attached view is returned at once`() = runBlocking {
        val provider = Provider()
        val view = galleryView()
        provider.galleryView = view
        assertSame(view, provider.startJumpTarget(timeoutMs = 0))
    }

    @Test
    fun `no view within the timeout gives null`() = runBlocking {
        assertNull(Provider().startJumpTarget(timeoutMs = 50))
    }
}
