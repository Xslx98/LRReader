package com.lanraragi.reader.ui

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lanraragi.reader.R
import com.lanraragi.reader.dao.DatabaseDowngradeException
import com.lanraragi.reader.module.AppModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Shows what the background boot work has to tell the user:
 * - the boot-failure recovery dialog (Retry / Reset database / Cancel) for a
 *   failure the profile loader reports in [AppModule.bootProfileLoadError]
 *   (audit 2026-10-06d STAB-02);
 * - a one-time notice that a corrupt database was moved aside and replaced
 *   by an empty one, from [AppModule.databaseResetNotice] (REL-02).
 *
 * The loader runs on `bootScope` and usually reports after MainActivity was
 * created, so both slots are collected while the host is STARTED rather than
 * read once in `onCreate`:
 * - a dialog appears whenever its value arrives, before or after `onCreate`;
 * - it waits while [isLockScreenUp] (the app lock was not passed yet) — the
 *   host calls [maybeShow] again once the lock screen is gone;
 * - the value stays set until the user answers, and each host instance shows
 *   one dialog per value: leaving STARTED dismisses it, so a recreated
 *   activity (rotation, theme switch) shows it again instead of a second copy,
 *   and a re-lock after the app went to the background does not leave it
 *   over the lock screen;
 * - answering clears the value (`compareAndSet`), so it is shown once.
 *
 * KeyStore-only failures do not come here: they flow through the reauth
 * dialog (`LRRAuthManager.isNeedsReauthentication`), which takes precedence.
 */
class BootNoticePresenter(
    private val activity: AppCompatActivity,
    private val isLockScreenUp: () -> Boolean,
    private val onRetry: () -> Unit,
    private val onResetDatabase: () -> Unit,
    private val onResetNoticeSeen: (Long) -> Unit,
    private val bootError: MutableStateFlow<Throwable?> = AppModule.bootProfileLoadError,
    private val resetNotice: MutableStateFlow<Long?> = AppModule.databaseResetNotice,
) {
    private var errorDialog: AlertDialog? = null
    private var shownError: Throwable? = null
    private var noticeDialog: AlertDialog? = null
    private var shownNotice: Long? = null

    /** Starts observing; call once from the host's `onCreate`. */
    fun install() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    launch { resetNotice.collect { maybeShow() } }
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
        val stamp = resetNotice.value
        if (stamp != null && stamp != shownNotice) {
            shownNotice = stamp
            noticeDialog = showResetNotice(stamp)
        }
        // Shown after the notice so the recovery dialog, which needs an
        // answer, ends up on top.
        val err = bootError.value
        if (err != null && err !== shownError) {
            shownError = err
            errorDialog = showBootFailureDialog(err)
        }
    }

    private fun showResetNotice(stamp: Long): AlertDialog =
        AlertDialog.Builder(activity)
            .setTitle(R.string.lrr_db_reset_notice_title)
            .setMessage(R.string.lrr_db_reset_notice_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                resetNotice.compareAndSet(stamp, null)
                noticeDialog = null
                onResetNoticeSeen(stamp)
            }
            .setCancelable(false)
            .show()

    private fun showBootFailureDialog(err: Throwable): AlertDialog {
        // A database from a newer app version gets its own explanation: the
        // file is intact and reinstalling that version brings everything back.
        val message = if (generateSequence(err) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is DatabaseDowngradeException }) {
            activity.getString(R.string.lrr_boot_db_newer_message)
        } else {
            activity.getString(R.string.lrr_boot_load_failed_message, err.message ?: err.javaClass.simpleName)
        }
        return AlertDialog.Builder(activity)
            .setTitle(R.string.lrr_boot_load_failed_title)
            .setMessage(message)
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

    /** Leaving STARTED: drop the dialogs; unanswered ones show again on the next start. */
    private fun dismissShown() {
        errorDialog?.dismiss()
        errorDialog = null
        shownError = null
        noticeDialog?.dismiss()
        noticeDialog = null
        shownNotice = null
    }

    private companion object {
        /** Cause-chain steps searched for the downgrade marker (guards a cyclic chain). */
        const val MAX_CAUSE_DEPTH = 8
    }
}
