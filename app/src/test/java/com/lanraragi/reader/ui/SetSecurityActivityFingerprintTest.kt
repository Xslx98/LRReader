package com.lanraragi.reader.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit 2026-10-06f R3: fingerprint unlock is saved on only for a pattern bound
 * to the keystore key; the lock screen switches an unbound one off again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SetSecurityActivityFingerprintTest {

    @Test
    fun an_unbound_pattern_never_turns_fingerprint_unlock_on() {
        assertFalse(SetSecurityActivity.fingerprintUnlockAfterSave(requested = true, keystoreBound = false))
        assertFalse(SetSecurityActivity.fingerprintUnlockAfterSave(requested = false, keystoreBound = false))
    }

    @Test
    fun a_bound_pattern_follows_the_checkbox() {
        assertTrue(SetSecurityActivity.fingerprintUnlockAfterSave(requested = true, keystoreBound = true))
        assertFalse(SetSecurityActivity.fingerprintUnlockAfterSave(requested = false, keystoreBound = true))
    }
}
