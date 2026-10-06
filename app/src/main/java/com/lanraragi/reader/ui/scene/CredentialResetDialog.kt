package com.lanraragi.reader.ui.scene

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.lanraragi.reader.LRReaderApplication
import com.lanraragi.reader.R
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.settings.AppLockGate

/**
 * The credential reset for users who never see the lock screen (audit
 * 2026-10-06b REL-01). When the secure store finished opening and failed
 * (for example it came from another phone, whose keystore key never travels)
 * and no app lock is set, the "credentials unavailable" dialogs get a button
 * that drops the unreadable store. The database (servers, library, history,
 * favourites, downloads) is not touched.
 */
internal object CredentialResetDialog {

    /** "Credentials could not be saved securely", with the reset when it applies. */
    fun showSecureStorageError(ctx: Context) {
        val builder = AlertDialog.Builder(ctx)
            .setTitle(R.string.lrr_keystore_failed_title)
            .setMessage(R.string.lrr_secure_storage_write_failed)
            .setPositiveButton(android.R.string.ok, null)
        offerResetIfStuck(builder, ctx).show()
    }

    /**
     * Add the reset button to [builder] when [LRRAuthManager.canOfferCredentialReset].
     * [onCancel] runs when the user backs out of the confirmation.
     */
    fun offerResetIfStuck(
        builder: AlertDialog.Builder,
        ctx: Context,
        onCancel: () -> Unit = {},
    ): AlertDialog.Builder {
        if (LRRAuthManager.canOfferCredentialReset()) {
            builder.setNegativeButton(R.string.lrr_reset_credentials) { _, _ -> confirm(ctx, onCancel) }
        }
        return builder
    }

    private fun confirm(ctx: Context, onCancel: () -> Unit) {
        AlertDialog.Builder(ctx)
            .setMessage(R.string.lrr_reset_credentials_confirm)
            .setCancelable(false)
            .setPositiveButton(R.string.lrr_reset_credentials) { _, _ ->
                LRRAuthManager.resetAppLockAndCredentials(ctx)
                AppLockGate.reset()
                (ctx.applicationContext as LRReaderApplication).restart()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .show()
    }
}
