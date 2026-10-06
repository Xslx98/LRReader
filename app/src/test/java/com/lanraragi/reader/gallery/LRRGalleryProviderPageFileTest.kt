package com.lanraragi.reader.gallery

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LRRGalleryProviderPageFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

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

    /** Audit 2026-10-06e P4-d: a handed-over damaged page is read from the reader cache. */
    @Test
    fun hybridMode_damagedPageHandedOver_usesReaderCache() {
        val dl = tmp.newFolder("dl")
        val cache = tmp.newFolder("cache")
        val store = HybridPageStore(dl, cache)
        val damaged = LRRGalleryProvider.resolvePageFile(store, cache, 3, "arc/003.png")

        store.handOverDamagedPage(3, damaged)

        assertEquals(File(cache, "page_3"), LRRGalleryProvider.resolvePageFile(store, cache, 3, "arc/003.png"))
        assertEquals(File(dl, "0005.png"), LRRGalleryProvider.resolvePageFile(store, cache, 4, "arc/004.png"))
    }
}
