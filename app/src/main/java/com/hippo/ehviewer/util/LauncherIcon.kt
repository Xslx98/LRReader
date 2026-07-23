package com.hippo.ehviewer.util

import android.content.Context
import android.graphics.Bitmap
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toBitmap
import com.hippo.ehviewer.R

/**
 * Renders the launcher icon into a raster bitmap for consumers that need one
 * (e.g. Notification.setLargeIcon). Must go through the Drawable pipeline:
 * BitmapFactory.decodeResource returns null for the anydpi-v26 adaptive-icon
 * XML, so decoding the resource directly only worked while legacy density
 * PNGs existed.
 */
object LauncherIcon {

    fun largeIconBitmap(context: Context): Bitmap? = runCatching {
        AppCompatResources.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap()
    }.getOrNull()
}
