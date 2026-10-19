package com.lanraragi.reader.ui.gallery

import android.content.Context
import android.graphics.Typeface
import com.hippo.android.resource.AttrResources
import com.lanraragi.framework.lib.glgallery.GalleryView
import com.lanraragi.framework.lib.yorozuya.ResourcesUtils
import com.lanraragi.reader.R
import com.lanraragi.reader.settings.ReadingSettings
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay

/** The GL page view of the reader: whether the device can run it, and how it is built. */
object ReaderGalleryViews {

    fun isEglAvailable(): Boolean {
        val egl = EGLContext.getEGL() as EGL10
        val display: EGLDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            ?: return false
        if (display == EGL10.EGL_NO_DISPLAY) {
            return false
        }
        val version = IntArray(2)
        if (!egl.eglInitialize(display, version)) {
            return false
        }
        var available = false
        try {
            val numConfig = IntArray(1)
            available = egl.eglChooseConfig(display, intArrayOf(EGL10.EGL_NONE), null, 0, numConfig)
                && numConfig[0] > 0
        } catch (ignored: Exception) {
            available = false
        } finally {
            egl.eglTerminate(display)
        }
        return available
    }

    /** The page view configured from the reading settings and the reader theme. */
    fun build(
        context: Context,
        adapter: GalleryView.Adapter,
        listener: GalleryView.Listener,
        startPage: Int,
    ): GalleryView {
        val resources = context.resources
        return GalleryView.Builder(context, adapter)
            .setListener(listener)
            .setLayoutMode(ReadingSettings.getReadingDirection())
            .setScaleMode(ReadingSettings.getPageScaling())
            .setStartPosition(ReadingSettings.getStartPosition())
            .setStartPage(startPage)
            .setBackgroundColor(AttrResources.getAttrColor(context, android.R.attr.colorBackground))
            .setEdgeColor(
                AttrResources.getAttrColor(context, R.attr.colorEdgeEffect) and 0xffffff or 0x33000000
            )
            .setPagerInterval(
                if (ReadingSettings.getShowPageInterval())
                    resources.getDimensionPixelOffset(R.dimen.gallery_pager_interval) else 0
            )
            // Vertical (LAYOUT_TOP_TO_BOTTOM) mode is the project's
            // long-strip / webtoon view: the standard convention
            // across Mihon / Tachiyomi / Yokai is *no* gap between
            // pages so the strip reads continuously. The user-visible
            // "show page interval" toggle still governs pager-mode
            // spacing (where the gap is a useful visual separator
            // between discrete pages).
            .setScrollInterval(0)
            .setPageMinHeight(resources.getDimensionPixelOffset(R.dimen.gallery_page_min_height))
            .setPageInfoInterval(resources.getDimensionPixelOffset(R.dimen.gallery_page_info_interval))
            .setProgressColor(
                ResourcesUtils.getAttrColor(context, androidx.appcompat.R.attr.colorPrimary)
            )
            .setProgressSize(resources.getDimensionPixelOffset(R.dimen.gallery_progress_size))
            .setPageTextColor(
                AttrResources.getAttrColor(context, android.R.attr.textColorSecondary)
            )
            .setPageTextSize(resources.getDimensionPixelOffset(R.dimen.gallery_page_text_size))
            .setPageTextTypeface(Typeface.DEFAULT)
            .setErrorTextColor(resources.getColor(R.color.red_500, null))
            .setErrorTextSize(resources.getDimensionPixelOffset(R.dimen.gallery_error_text_size))
            .setDefaultErrorString(resources.getString(R.string.error_unknown))
            .setEmptyString(resources.getString(R.string.error_empty))
            .build()
    }
}
