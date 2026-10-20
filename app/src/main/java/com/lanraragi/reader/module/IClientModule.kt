package com.lanraragi.reader.module

import coil3.ImageLoader
import com.lanraragi.framework.conaco.Conaco
import com.lanraragi.reader.ImageBitmapHelper
import com.lanraragi.framework.lib.image.Image

/**
 * Abstraction over [ClientModule] to allow ServiceRegistry consumers to depend on the
 * contract rather than the concrete implementation. Enables test-time substitution with
 * memory-only Conaco stubs.
 */
interface IClientModule {

    /** Shared bitmap decoder + memory pool used by the image loader. */
    val imageBitmapHelper: ImageBitmapHelper

    /** Conaco image loader with memory + disk cache, backed by the network module client. */
    val conaco: Conaco<Image>

    /**
     * Coil image loader for archive covers (2.4 P1 pilot: home list only),
     * backed by the network module's thumbnail client so the auth
     * interceptor and server trust apply.
     */
    val coverImageLoader: ImageLoader

    /** Clears Conaco's in-memory cache. Used under memory pressure. */
    fun clearMemoryCache()
}
