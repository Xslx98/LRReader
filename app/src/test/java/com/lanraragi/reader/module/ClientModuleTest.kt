package com.lanraragi.reader.module

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for Conaco's cache sizing in [ClientModule]: the thumbnail memory
 * cache and the disk cache tiers, both from total RAM.
 */
class ClientModuleTest {

    private val MB = 1024L * 1024

    // --- Thumbnail memory cache: RAM/16 within 32..96 MB, 16 MB on low-RAM (audit C19) ---

    @Test
    fun thumbMemoryCache_isASixteenthOfRam_within32to96Mb() {
        assertEquals(32 * MB, ClientModule.thumbMemoryCacheSize(512 * MB, lowRam = false))
        assertEquals(64 * MB, ClientModule.thumbMemoryCacheSize(1024 * MB, lowRam = false))
        assertEquals(96 * MB, ClientModule.thumbMemoryCacheSize(2048 * MB, lowRam = false))
        assertEquals(96 * MB, ClientModule.thumbMemoryCacheSize(12288 * MB, lowRam = false))
    }

    @Test
    fun thumbMemoryCache_lowRamDevice_gets16Mb() {
        assertEquals(16 * MB, ClientModule.thumbMemoryCacheSize(1024 * MB, lowRam = true))
    }

    // --- Disk cache tiers on total RAM: 80 / 160 / 320 MB (audit C12/C19) ---

    @Test
    fun tieredDiskCacheSize_belowThreeGb_returns80MB() {
        assertEquals(80 * MB, ClientModule.tieredDiskCacheSize(2048 * MB, lowRam = false))
        assertEquals(80 * MB, ClientModule.tieredDiskCacheSize(3071 * MB, lowRam = false))
    }

    @Test
    fun tieredDiskCacheSize_threeToSixGb_returns160MB() {
        assertEquals(160 * MB, ClientModule.tieredDiskCacheSize(3072 * MB, lowRam = false))
        assertEquals(160 * MB, ClientModule.tieredDiskCacheSize(6143 * MB, lowRam = false))
    }

    @Test
    fun tieredDiskCacheSize_sixGbAndUp_returns320MB() {
        assertEquals(320 * MB, ClientModule.tieredDiskCacheSize(6144 * MB, lowRam = false))
        assertEquals(320 * MB, ClientModule.tieredDiskCacheSize(16384 * MB, lowRam = false))
    }

    @Test
    fun tieredDiskCacheSize_lowRamDevice_returns80MB() {
        assertEquals(80 * MB, ClientModule.tieredDiskCacheSize(8192 * MB, lowRam = true))
    }
}
