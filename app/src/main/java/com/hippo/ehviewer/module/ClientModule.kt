package com.hippo.ehviewer.module

import android.content.Context
import com.hippo.conaco.Conaco
import com.hippo.ehviewer.ImageBitmapHelper
import com.hippo.lib.image.Image
import java.io.File

/**
 * Manages client-side singletons: Conaco (image loader) and
 * ImageBitmapHelper (bitmap decoder).
 * Extracted from EhApplication to reduce its responsibility scope.
 */
class ClientModule(
    private val context: Context,
    private val networkModule: INetworkModule
) : IClientModule {

    override val imageBitmapHelper: ImageBitmapHelper by lazy { ImageBitmapHelper() }

    override val conaco: Conaco<Image> by lazy {
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

    override fun clearMemoryCache() {
        conaco.beerBelly?.clearMemory()
    }

    private companion object {
        fun memoryCacheMaxSize(): Int {
            val maxMemory = Runtime.getRuntime().maxMemory() / 8
            return maxMemory.coerceIn(20L * 1024 * 1024, 64L * 1024 * 1024).toInt()
        }
    }
}
