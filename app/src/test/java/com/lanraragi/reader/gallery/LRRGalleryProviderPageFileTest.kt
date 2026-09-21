package com.lanraragi.reader.gallery

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LRRGalleryProviderPageFileTest {

    private val cacheDir = File("cache")
    private val store = HybridPageStore(File("dl"), cacheDir)

    @Test
    fun streamingMode_usesReaderCache() {
        assertEquals(
            File(cacheDir, "page_3"),
            LRRGalleryProvider.resolvePageFile(null, cacheDir, 3, "arc/003.png"),
        )
    }

    @Test
    fun hybridMode_usesWorkerNamingInDownloadDir() {
        assertEquals(
            File("dl", "0004.png"),
            LRRGalleryProvider.resolvePageFile(store, cacheDir, 3, "arc/003.png"),
        )
    }

    @Test
    fun hybridMode_fallsBackToCacheUntilPageListIsKnown() {
        assertEquals(
            File(cacheDir, "page_3"),
            LRRGalleryProvider.resolvePageFile(store, cacheDir, 3, null),
        )
    }
}
