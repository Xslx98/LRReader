package com.lanraragi.reader.ui.scene

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.lanraragi.reader.R
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.ui.SetSecurityActivity

/**
 * One-time notice that an app lock saved as the pre-PBKDF2 SHA-256 hash was
 * removed on upgrade (audit 2026-10-06d SEC-08). That hash cannot be checked
 * any more, so the app opens unlocked; without this notice the user would
 * not know the lock is gone.
 *
 * The flag is cleared only when the user answers (a button, or Back), not
 * when the dialog is shown: MainActivity is recreated on rotation, and a
 * notice consumed on display was lost by one turn of the phone (audit
 * 2026-10-06e). The recreated activity calls [showIfPending] again.
 */
internal object LegacyLockRemovedNotice {

    /** Shows the notice if init dropped such a lock and the user has not answered it yet. */
    fun showIfPending(activity: Activity) {
        if (!LRRAuthManager.isLegacyLockRemovedNoticePending()) return
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.security_legacy_lock_removed_title)
            .setMessage(R.string.security_legacy_lock_removed_message)
            .setPositiveButton(R.string.set_pattern_protection) { _, _ ->
                LRRAuthManager.clearLegacyLockRemovedNotice()
                activity.startActivity(Intent(activity, SetSecurityActivity::class.java))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                LRRAuthManager.clearLegacyLockRemovedNotice()
            }
            // Back is an answer; a stray tap outside is not.
            .setOnCancelListener { LRRAuthManager.clearLegacyLockRemovedNotice() }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        // Close it with the activity (rotation) without counting as an
        // answer: dismiss() does not call the cancel listener.
        (activity as? LifecycleOwner)?.lifecycle?.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                dialog.dismiss()
            }
        })
        dialog.show()
    }
}
