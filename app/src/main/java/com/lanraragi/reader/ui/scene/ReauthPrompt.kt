package com.lanraragi.reader.ui.scene

import androidx.annotation.StringRes
import com.lanraragi.reader.R

/**
 * Wording of the "re-enter your credentials" prompts. Reauth is needed either
 * because the secure store failed (keystore wording) or because the store
 * works but saved API keys are missing — after "Reset saved credentials" or a
 * backup restored on a new device — where the keystore wording was wrong.
 */
internal object ReauthPrompt {

    data class Text(@StringRes val title: Int, @StringRes val message: Int)

    /**
     * Scene argument set by the startup prompt when it already explained the
     * missing keys and is sending the user to the server list, so the list
     * does not show a second dialog saying the same thing.
     */
    const val ARG_KEYS_MISSING_PROMPTED = "reauth_keys_missing_prompted"

    private val KEYS_MISSING = Text(R.string.reauth_keys_missing_title, R.string.reauth_keys_missing_message)

    /** The startup prompt (MainActivity). */
    fun startup(storeAvailable: Boolean): Text =
        if (storeAvailable) KEYS_MISSING else Text(R.string.lrr_keystore_failed_title, R.string.lrr_keystore_failed_message)

    /** The server list's prompt. */
    fun serverList(storeAvailable: Boolean): Text =
        if (storeAvailable) KEYS_MISSING else Text(R.string.reauth_required_title, R.string.reauth_required_message)

    /** Whether the server list shows its own prompt (see [ARG_KEYS_MISSING_PROMPTED]). */
    fun serverListShouldPrompt(keysMissingPrompted: Boolean, storeAvailable: Boolean): Boolean =
        !(keysMissingPrompted && storeAvailable)
}
