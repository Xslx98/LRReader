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
        view.showInfo()
        view.setImage(null)
        view.setPage(index + 1)
        view.setProgress(GalleryPageView.PROGRESS_INDETERMINATE)
        view.clearError()
    }

    override fun onUnbind(view: GalleryPageView, index: Int) {
        provider.cancelRequest(index)
        view.setImage(null)
        view.clearError()
    }

    override fun getError(): String? = provider.getError()

    override fun size(): Int = provider.size()

    override fun onDataChanged() {
        galleryView?.onDataChanged()
    }

    override fun onPageWait(index: Int) {
        val page = findPageByIndex(index)
        if (page != null) {
            // Don't tear a page that already shows its image back down to the
            // white loading spinner. A wait can be re-delivered for a visible,
            // already-loaded page (cache-eviction re-bind, preload churn, the
            // recycled-image re-request path) and blanking it to showInfo() was
            // the visible image -> white -> image flicker. Keep the image up;
            // if a decode is genuinely in flight, onPageSucceed replaces it in
            // place when it lands.
            if (page.isLoaded) {
                return
            }
            page.showInfo()
            page.setImage(null)
            page.setPage(index + 1)
            page.setProgress(GalleryPageView.PROGRESS_INDETERMINATE)
            page.clearError()
        }
    }

    override fun onPagePercent(index: Int, percent: Float) {
        val page = findPageByIndex(index)
        if (page != null) {
            // Same guard as onPageWait: a percent update for an
            // already-displayed page must not blank it back to the spinner.
            if (page.isLoaded) {
                return
            }
            page.showInfo()
            page.setImage(null)
            page.setPage(index + 1)
            page.setProgress(percent)
            page.clearError()
        }
    }

    override fun onPageSucceed(index: Int, image: ImageWrapper) {
        val page = findPageByIndex(index)
        if (page != null) {
            if (image.obtain()) {
                val imageTexture = ImageTexture(image)
                uploader.addTexture(imageTexture)
                page.showImage()
                page.setImage(imageTexture)
                page.setPage(index + 1)
                page.setProgress(GalleryPageView.PROGRESS_GONE)
                page.clearError()
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
        val page = findPageByIndex(index)
        if (page != null) {
            page.showInfo()
            page.setImage(null)
            page.setPage(index + 1)
            page.setProgress(GalleryPageView.PROGRESS_GONE)
            if (error == null) {
                page.clearError()
            } else {
                // The page was found through the gallery view, so it is set.
                galleryView?.let { page.setError(error, it) }
            }
        }
    }

    override fun onDataChanged(index: Int) {
        val page = findPageByIndex(index)
        if (page != null) {
            provider.request(index)
        }
    }

    private fun findPageByIndex(index: Int): GalleryPageView? = galleryView?.findPageByIndex(index)
}
