package com.lanraragi.reader.client.api

import android.security.keystore.KeyProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit 2026-10-06d STAB-01: the pattern key's spec called the API 30
 * `setUserAuthenticationParameters` on every version, so setting a pattern
 * with an enrolled fingerprint crashed Android 9/10 with NoSuchMethodError.
 * Robolectric runs each test against that SDK's framework classes, so an
 * ungated call fails here exactly as on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PatternKeySpecTest {

    @Test
    @Config(sdk = [28])
    fun android9_keyNeedsABiometricForEveryUse() {
        assertPerUseAuthBelowApi30()
    }

    @Test
    @Config(sdk = [29])
    fun android10_keyNeedsABiometricForEveryUse() {
        assertPerUseAuthBelowApi30()
    }

    @Test
    @Config(sdk = [30])
    fun android11_keyNeedsAStrongBiometricForEveryUse() {
        val spec = LRRAuthManager.patternKeySpec()

        assertTrue(spec.isUserAuthenticationRequired)
        assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, spec.userAuthenticationType)
        assertEquals("0 s: every use needs its own authentication", 0, spec.userAuthenticationValidityDurationSeconds)
        assertEquals(listOf(KeyProperties.BLOCK_MODE_GCM), spec.blockModes.toList())
    }

    private fun assertPerUseAuthBelowApi30() {
        val spec = LRRAuthManager.patternKeySpec()

        assertTrue(spec.isUserAuthenticationRequired)
        assertEquals("-1: every use needs a biometric", -1, spec.userAuthenticationValidityDurationSeconds)
        assertEquals(listOf(KeyProperties.BLOCK_MODE_GCM), spec.blockModes.toList())
        assertEquals(256, spec.keySize)
    }
}
