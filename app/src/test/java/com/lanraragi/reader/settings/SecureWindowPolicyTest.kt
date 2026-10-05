package com.lanraragi.reader.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit C48 / SEC-14 (ruling 2026-10-04): FLAG_SECURE for a locked app below API 33 only. */
class SecureWindowPolicyTest {

    private val api32 = 32
    private val api33 = 33

    @Test
    fun user_secure_mode_always_wins() {
        assertTrue(SecureWindowPolicy.wantsSecureFlag(api33, true, false, true))
        assertTrue(SecureWindowPolicy.wantsSecureFlag(api32, true, false, true))
    }

    @Test
    fun locked_app_below_33_is_secure_on_pause_and_while_locked() {
        assertTrue(SecureWindowPolicy.wantsSecureFlag(api32, false, true, resumedAndUnlocked = false))
    }

    @Test
    fun unlocked_and_resumed_keeps_screenshots() {
        assertFalse(SecureWindowPolicy.wantsSecureFlag(api32, false, true, resumedAndUnlocked = true))
    }

    @Test
    fun api_33_and_up_rely_on_recents_screenshot_switch() {
        assertFalse(SecureWindowPolicy.wantsSecureFlag(api33, false, true, resumedAndUnlocked = false))
    }

    @Test
    fun no_lock_no_flag() {
        assertFalse(SecureWindowPolicy.wantsSecureFlag(api32, false, false, resumedAndUnlocked = false))
    }
}
