package com.hippo.ehviewer.module

import com.hippo.conaco.Conaco
import com.hippo.ehviewer.ImageBitmapHelper
import com.hippo.lib.image.Image

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

    /** Clears Conaco's in-memory cache. Used under memory pressure. */
    fun clearMemoryCache()
}
