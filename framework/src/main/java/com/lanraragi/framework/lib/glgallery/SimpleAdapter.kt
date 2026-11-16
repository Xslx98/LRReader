/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lanraragi.framework.lib.glgallery

import com.lanraragi.framework.lib.glview.image.ImageTexture
import com.lanraragi.framework.lib.glview.image.ImageWrapper
import com.lanraragi.framework.lib.glview.view.GLRootView

open class SimpleAdapter(
    glRootView: GLRootView,
    private val provider: GalleryProvider,
) : GalleryView.Adapter(), GalleryProvider.Listener {

    private val uploader: ImageTexture.Uploader = ImageTexture.Uploader(glRootView)

    fun clearUploader() {
        uploader.clear()
    }

    override fun onBind(view: GalleryPageView, index: Int) {
        provider.request(index)
        view.render(PageCellState.Wait, index + 1)
    }

    override fun onUnbind(view: GalleryPageView, index: Int) {
        provider.cancelRequest(index)
        view.reset()
    }

    override fun getError(): String? = provider.getError()

    override fun size(): Int = provider.size()

    override fun pageAspect(index: Int): Float = provider.pageAspect(index)

    override fun isPairBreak(index: Int): Boolean = provider.isPairBreak(index)

    override fun onDataChanged() {
        galleryView?.onDataChanged()
    }

    /**
     * The page cell bound to [index], unless it already shows its image: a
     * wait, percent or failure can be re-delivered for a visible, loaded page
     * (cache-eviction re-bind, preload churn, the recycled-image re-request
     * path), and tearing it down to the spinner was the visible image ->
     * white -> image flicker. A decode genuinely in flight replaces the image
     * in place when it lands. A failure is not guarded: the page menu's
     * Refresh drops the cached page and re-requests it, and when that fails
     * the error text (tap to retry) must replace the stale image.
     */
    private fun unloadedPage(index: Int): GalleryPageView? = findPageByIndex(index)?.takeUnless { it.isLoaded }

    override fun onPageWait(index: Int) {
        unloadedPage(index)?.render(PageCellState.Wait, index + 1)
    }

    override fun onPagePercent(index: Int, percent: Float) {
        unloadedPage(index)?.render(PageCellState.Progress(percent), index + 1)
    }

    override fun onPageSucceed(index: Int, image: ImageWrapper) {
        val page = findPageByIndex(index)
        if (page != null) {
            if (image.obtain()) {
                val imageTexture = ImageTexture(image)
                uploader.addTexture(imageTexture)
                page.render(PageCellState.Loaded, index + 1, imageTexture)
                // The decode recorded the page's size (GalleryProvider.pageAspect).
                galleryView?.onPageAspectKnown(index)
            } else {
                // The image is recycled, request again.
                // UPSTREAM-LEGACY: potential infinite request loop if adapter keeps returning RESULT_ERROR
                provider.request(index)
            }
        } else if (image.isUncached) {
            // The target page is no longer bound (scrolled away before the
            // decode finished). An animated image (or one bigger than the
            // whole cache budget) is never cached, so its refcount is still 0
            // and nothing else will recycle it — release it here to free the
            // native memory. Other pages are held by the provider's LRU cache
            // and recycled on eviction.
            image.release()
        }
    }

    override fun onPageFailed(index: Int, error: String?) {
        // The page was found through the gallery view, so it is set.
        findPageByIndex(index)?.render(PageCellState.Error(error), index + 1, galleryView = galleryView)
    }

    override fun onDataChanged(index: Int) {
        val page = findPageByIndex(index)
        if (page != null) {
            provider.request(index)
        }
    }

    private fun findPageByIndex(index: Int): GalleryPageView? = galleryView?.findPageByIndex(index)
}
