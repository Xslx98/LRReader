package com.hippo.ehviewer.module

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ClientModule.tieredCacheSize] — the device-tier-based
 * memory cache sizing logic used by Conaco's image loader.
 *
 * Each test verifies a tier boundary using the per-app heap limit
 * (`Runtime.getRuntime().maxMemory()`) as input.
 */
class ClientModuleTest {

    private val MB = 1024L * 1024

    // --- Tier 1: < 512 MB → 16 MB ---

    @Test
    fun tieredCacheSize_256MB_returns16MB() {
        assertEquals(16 * MB, ClientModule.tieredCacheSize(256 * MB))
    }

    @Test
    fun tieredCacheSize_511MB_returns16MB() {
        assertEquals(16 * MB, ClientModule.tieredCacheSize(511 * MB))
    }

    // --- Tier 2: 512 MB ..< 1 GB → 32 MB ---

    @Test
    fun tieredCacheSize_512MB_returns32MB() {
        assertEquals(32 * MB, ClientModule.tieredCacheSize(512 * MB))
    }

    @Test
    fun tieredCacheSize_999MB_returns32MB() {
        assertEquals(32 * MB, ClientModule.tieredCacheSize(999 * MB))
    }

    // --- Tier 3: 1 GB ..< 3 GB → 80 MB ---

    @Test
    fun tieredCacheSize_1GB_returns80MB() {
        assertEquals(80 * MB, ClientModule.tieredCacheSize(1024 * MB))
    }

    @Test
    fun tieredCacheSize_2900MB_returns80MB() {
        // 2.9 GB ≈ 2969.6 MB — use 2969 MB to stay below 3 GB
        assertEquals(80 * MB, ClientModule.tieredCacheSize(2969 * MB))
    }

    // --- Tier 4: >= 3 GB → 128 MB ---

    @Test
    fun tieredCacheSize_3GB_returns128MB() {
        assertEquals(128 * MB, ClientModule.tieredCacheSize(3072 * MB))
    }

    @Test
    fun tieredCacheSize_8GB_returns128MB() {
        assertEquals(128 * MB, ClientModule.tieredCacheSize(8192 * MB))
    }
}
