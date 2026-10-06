package com.lanraragi.reader.ui.scene

import androidx.annotation.StringRes
import com.lanraragi.reader.R

/**
 * Wording and buttons of the lock screen's "secure storage unavailable"
 * prompt (audit 2026-10-06d SEC-01). The reset, which removes the pattern
 * and every saved API key, appears only from the second launch in a row
 * that failed; before that the prompt says the failure may be temporary and
 * offers only "Try again". A user who reached the lock screen only because
 * the lock state could not be read (no lock known to be set) is not told
 * about resetting an app lock they may never have set.
 */
internal object StorageUnavailablePrompt {

    /** [resetButton] and [resetConfirm] are null when no reset is offered. */
    data class Text(
        @StringRes val message: Int,
        @StringRes val resetButton: Int?,
        @StringRes val resetConfirm: Int?,
    )

    fun of(lockKnownSet: Boolean, offerReset: Boolean): Text = when {
        lockKnownSet && offerReset -> Text(
            R.string.security_storage_unavailable_message,
            R.string.security_reset_app_lock,
            R.string.security_reset_app_lock_confirm,
        )
        lockKnownSet -> Text(R.string.security_storage_unavailable_first_message, null, null)
        offerReset -> Text(
            R.string.security_lock_state_unknown_message,
            R.string.lrr_reset_credentials,
            R.string.lrr_reset_credentials_confirm,
        )
        else -> Text(R.string.security_lock_state_unknown_first_message, null, null)
    }
}
