package com.lanraragi.reader.ui

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lanraragi.reader.R
import com.lanraragi.reader.module.AppModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Shows the boot-failure recovery dialog (Retry / Reset database / Cancel)
 * for a failure the background profile loader reports in
 * [AppModule.bootProfileLoadError] (audit 2026-10-06d STAB-02).
 *
 * The loader runs on `bootScope` and usually fails after MainActivity was
 * created, so the error is collected while the host is STARTED rather than
 * read once in `onCreate`:
 * - the dialog appears whenever the failure arrives, before or after `onCreate`;
 * - it waits while [isLockScreenUp] (the app lock was not passed yet) — the
 *   host calls [maybeShow] again once the lock screen is gone;
 * - the error stays set until the user answers, and each host instance shows
 *   one dialog per error: leaving STARTED dismisses it, so a recreated
 *   activity (rotation, theme switch) shows it again instead of a second copy,
 *   and a re-lock after the app went to the background does not leave it
 *   over the lock screen;
 * - answering clears the error (`compareAndSet`), so it is shown once per failure.
 *
 * KeyStore-only failures do not come here: they flow through the reauth
 * dialog (`LRRAuthManager.isNeedsReauthentication`), which takes precedence.
 */
class BootNoticePresenter(
    private val activity: AppCompatActivity,
    private val isLockScreenUp: () -> Boolean,
    private val onRetry: () -> Unit,
    private val onResetDatabase: () -> Unit,
    private val bootError: MutableStateFlow<Throwable?> = AppModule.bootProfileLoadError,
) {
    private var errorDialog: AlertDialog? = null
    private var shownError: Throwable? = null

    /** Starts observing; call once from the host's `onCreate`. */
    fun install() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    bootError.collect { maybeShow() }
                } finally {
                    dismissShown()
                }
            }
        }
    }

    /** Shows what is pending, if the host is started and not behind the lock screen. Main thread. */
    fun maybeShow() {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || activity.isFinishing) return
        if (isLockScreenUp()) return
        val err = bootError.value
        if (err != null && err !== shownError) {
            shownError = err
            errorDialog = showBootFailureDialog(err)
        }
    }

    private fun showBootFailureDialog(err: Throwable): AlertDialog {
        val detail = err.message ?: err.javaClass.simpleName
        return AlertDialog.Builder(activity)
            .setTitle(R.string.lrr_boot_load_failed_title)
            .setMessage(activity.getString(R.string.lrr_boot_load_failed_message, detail))
            .setPositiveButton(R.string.lrr_boot_load_failed_retry) { _, _ ->
                acknowledge(err)
                onRetry()
            }
            .setNegativeButton(R.string.lrr_boot_load_failed_reset_db) { _, _ ->
                acknowledge(err)
                onResetDatabase()
            }
            .setNeutralButton(android.R.string.cancel) { _, _ -> acknowledge(err) }
            .setCancelable(false)
            .show()
    }

    private fun acknowledge(err: Throwable) {
        bootError.compareAndSet(err, null)
        errorDialog = null
    }

    /** Leaving STARTED: drop the dialog; the error, if unanswered, shows again on the next start. */
    private fun dismissShown() {
        errorDialog?.dismiss()
        errorDialog = null
        shownError = null
    }
}
