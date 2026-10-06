package com.lanraragi.reader.ui.scene

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.lanraragi.reader.R
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.ui.SetSecurityActivity

/**
 * One-time notice that an app lock saved as the pre-PBKDF2 SHA-256 hash was
 * removed on upgrade (audit 2026-10-06d SEC-08). That hash cannot be checked
 * any more, so the app opens unlocked; without this notice the user would
 * not know the lock is gone.
 */
internal object LegacyLockRemovedNotice {

    /** Shows the notice if init dropped such a lock and it was not shown yet. */
    fun showIfPending(activity: Activity) {
        if (!LRRAuthManager.consumeLegacyLockRemovedNotice()) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.security_legacy_lock_removed_title)
            .setMessage(R.string.security_legacy_lock_removed_message)
            .setPositiveButton(R.string.set_pattern_protection) { _, _ ->
                activity.startActivity(Intent(activity, SetSecurityActivity::class.java))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
