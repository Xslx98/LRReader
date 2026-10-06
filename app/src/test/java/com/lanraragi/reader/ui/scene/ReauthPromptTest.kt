package com.lanraragi.reader.ui.scene

import com.lanraragi.reader.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * After "Reset saved credentials" (or a backup restored on a new device) the
 * secure store works but the API keys are gone; the prompts used to blame an
 * unavailable keystore and showed two dialogs in a row.
 */
class ReauthPromptTest {

    private val keysMissing = ReauthPrompt.Text(R.string.reauth_keys_missing_title, R.string.reauth_keys_missing_message)

    @Test
    fun storeAvailableButKeysMissingUsesTheKeysMissingWording() {
        assertEquals(keysMissing, ReauthPrompt.startup(storeAvailable = true))
        assertEquals(keysMissing, ReauthPrompt.serverList(storeAvailable = true))
    }

    @Test
    fun storeFailureKeepsTheKeystoreWording() {
        assertEquals(
            ReauthPrompt.Text(R.string.lrr_keystore_failed_title, R.string.lrr_keystore_failed_message),
            ReauthPrompt.startup(storeAvailable = false),
        )
        assertEquals(
            ReauthPrompt.Text(R.string.reauth_required_title, R.string.reauth_required_message),
            ReauthPrompt.serverList(storeAvailable = false),
        )
    }

    @Test
    fun serverListSkipsItsPromptOnlyAfterTheStartupKeysMissingPrompt() {
        assertFalse(ReauthPrompt.serverListShouldPrompt(keysMissingPrompted = true, storeAvailable = true))
        assertTrue(ReauthPrompt.serverListShouldPrompt(keysMissingPrompted = false, storeAvailable = true))
        // A store failure still gets the list's own prompt (it may carry the reset).
        assertTrue(ReauthPrompt.serverListShouldPrompt(keysMissingPrompted = true, storeAvailable = false))
        assertTrue(ReauthPrompt.serverListShouldPrompt(keysMissingPrompted = false, storeAvailable = false))
    }
}
