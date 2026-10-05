package com.lanraragi.reader.module

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for Conaco's cache sizing in [ClientModule]: the thumbnail memory
 * cache (total RAM) and the disk cache tiers (per-app heap limit).
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

    // --- Disk cache tiers: 80 / 160 / 320 MB ---

    @Test
    fun tieredDiskCacheSize_256MB_returns80MB() {
        assertEquals(80 * MB, ClientModule.tieredDiskCacheSize(256 * MB))
    }

    @Test
    fun tieredDiskCacheSize_511MB_returns80MB() {
        assertEquals(80 * MB, ClientModule.tieredDiskCacheSize(511 * MB))
    }

    @Test
    fun tieredDiskCacheSize_512MB_returns160MB() {
        assertEquals(160 * MB, ClientModule.tieredDiskCacheSize(512 * MB))
    }

    @Test
    fun tieredDiskCacheSize_999MB_returns160MB() {
        assertEquals(160 * MB, ClientModule.tieredDiskCacheSize(999 * MB))
    }

    @Test
    fun tieredDiskCacheSize_1GB_returns320MB() {
        assertEquals(320 * MB, ClientModule.tieredDiskCacheSize(1024 * MB))
    }

    @Test
    fun tieredDiskCacheSize_8GB_returns320MB() {
        assertEquals(320 * MB, ClientModule.tieredDiskCacheSize(8192 * MB))
    }
}
