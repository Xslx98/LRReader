package com.lanraragi.reader.ui

import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lanraragi.reader.R
import com.lanraragi.reader.Settings
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.DatabaseQuarantine
import com.lanraragi.reader.dao.DatabaseResetNotice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The destructive "reset database" branch of the boot-failure dialog
 * ([BootNoticePresenter]): a second confirm, then the database is moved aside
 * and [restart] rebuilds the process on an empty one.
 */
class DatabaseResetFlow(
    private val activity: AppCompatActivity,
    private val restart: () -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Second-stage confirm. Cancellable so back / outside-tap aborts; the
     * positive button calls [resetDatabaseAndRestart].
     */
    fun showConfirm() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.lrr_boot_load_failed_reset_db_confirm_title)
            .setMessage(R.string.lrr_boot_load_failed_reset_db_confirm_message)
            .setPositiveButton(R.string.lrr_boot_load_failed_reset_db_confirm_yes) { _, _ ->
                resetDatabaseAndRestart()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(true)
            .show()
    }

    /**
     * Move `eh.db` (plus its `-journal`, `-wal`, `-shm` siblings) aside to
     * `eh.db.broken-<millis>` on IO, then trigger a process restart so the
     * next `AppDatabase.getInstance` call rebuilds an empty schema. The old
     * file is kept for manual recovery (audit C04).
     */
    private fun resetDatabaseAndRestart() {
        activity.lifecycleScope.launch {
            withContext(ioDispatcher) {
                try {
                    val moved = DatabaseQuarantine.quarantine(
                        activity.applicationContext.getDatabasePath(AppDatabase.DB_NAME)
                    )
                    // The user chose this reset: no "database was damaged" notice
                    // after the restart (REL-02). Committed, the process dies next.
                    val stamp = moved?.let { DatabaseQuarantine.stampOf(it, AppDatabase.DB_NAME) }
                    if (stamp != null) DatabaseResetNotice.markSeen(Settings.getPreferences(), stamp, commit = true)
                } catch (t: Throwable) {
                    Log.e(TAG, "database quarantine failed during boot-failure reset", t)
                }
            }
            restart()
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
