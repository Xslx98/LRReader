package com.hippo.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Same-key short-circuit policy for LoadImageView / LoadImageViewNew.
 *
 * load() used to unconditionally build a new ConacoTask; Conaco.load cancels
 * the unikery's in-flight task first, and a cancelled network fetch discards
 * its partially downloaded bytes (putToDiskCache=false → removeFromDisk). So
 * any rebind of a row whose thumbnail was mid-flight restarted the fetch from
 * byte 0. A load for the SAME key while that key is already in flight must be
 * a no-op; everything else proceeds.
 */
class LoadImageSameKeyPolicyTest {

    @Test
    fun `same key in flight is skipped`() {
        assertTrue(
            LoadImageSameKeyPolicy.shouldSkipLoad(
                "k1", "k1", failed = false, useNetworkMatches = true, inFlight = true,
            ),
        )
    }

    @Test
    fun `different key always loads`() {
        assertFalse(
            LoadImageSameKeyPolicy.shouldSkipLoad(
                "k1", "k2", failed = false, useNetworkMatches = true, inFlight = true,
            ),
        )
    }

    @Test
    fun `no current key always loads`() {
        assertFalse(
            LoadImageSameKeyPolicy.shouldSkipLoad(
                null, "k1", failed = false, useNetworkMatches = true, inFlight = true,
            ),
        )
    }

    @Test
    fun `not in flight loads - completed thumbs refresh via memory cache`() {
        assertFalse(
            LoadImageSameKeyPolicy.shouldSkipLoad(
                "k1", "k1", failed = false, useNetworkMatches = true, inFlight = false,
            ),
        )
    }

    @Test
    fun `failed state always retries`() {
        assertFalse(
            LoadImageSameKeyPolicy.shouldSkipLoad(
                "k1", "k1", failed = true, useNetworkMatches = true, inFlight = true,
            ),
        )
    }

    @Test
    fun `useNetwork upgrade is not skipped`() {
        assertFalse(
            LoadImageSameKeyPolicy.shouldSkipLoad(
                "k1", "k1", failed = false, useNetworkMatches = false, inFlight = true,
            ),
        )
    }
}
