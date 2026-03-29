package com.hippo.ehviewer.module

import android.content.Context
import com.hippo.conaco.Conaco
import com.hippo.ehviewer.ImageBitmapHelper
import com.hippo.ehviewer.client.EhClient
import com.hippo.lib.image.Image
import com.hippo.lib.yorozuya.OSUtils
import java.io.File

/**
 * Manages client-side singletons: EhClient (API), Conaco (image loader),
 * and ImageBitmapHelper (bitmap decoder).
 * Extracted from EhApplication to reduce its responsibility scope.
 */
class ClientModule(
    private val context: Context,
    private val networkModule: NetworkModule
) {

    val ehClient: EhClient by lazy { EhClient(context) }

    val imageBitmapHelper: ImageBitmapHelper by lazy { ImageBitmapHelper() }

    val conaco: Conaco<Image> by lazy {
        Conaco.Builder<Image>().apply {
            hasMemoryCache = true
            memoryCacheMaxSize = memoryCacheMaxSize()
            hasDiskCache = true
            diskCacheDir = File(context.cacheDir, "thumb")
            diskCacheMaxSize = 320 * 1024 * 1024 // 320MB
            okHttpClient = networkModule.okHttpClient
            objectHelper = imageBitmapHelper
            debug = false
        }.build()
    }

    fun clearMemoryCache() {
        conaco.beerBelly?.clearMemory()
    }

    private companion object {
        fun memoryCacheMaxSize(): Int =
            minOf(20 * 1024 * 1024, OSUtils.getAppMaxMemory().toInt())
    }
}
