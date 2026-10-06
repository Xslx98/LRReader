package com.lanraragi.reader

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.core.content.IntentCompat

/**
 * The relaunch half of [ProcessRebirth]. Declared non-exported, without UI and
 * in its own `:phoenix` process, so it outlives the process it kills.
 * [LRReaderApplication.onCreate] returns early in this process.
 */
class PhoenixActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        relaunch(intent)
        finish()
        Process.killProcess(Process.myPid())
    }

    /** Kill the old main process, then start the app's launch intent. */
    internal fun relaunch(intent: Intent?) {
        val mainPid = intent?.getIntExtra(ProcessRebirth.EXTRA_MAIN_PID, 0) ?: 0
        if (mainPid > 0 && mainPid != Process.myPid()) {
            Process.killProcess(mainPid)
        }
        val launch = intent?.let {
            IntentCompat.getParcelableExtra(it, ProcessRebirth.EXTRA_LAUNCH_INTENT, Intent::class.java)
        }
        // Only ever relaunch this app (the activity is not exported, but the
        // launch intent is still checked before it is started).
        if (launch != null && launch.component?.packageName == packageName) {
            startActivity(launch)
        }
    }
}
