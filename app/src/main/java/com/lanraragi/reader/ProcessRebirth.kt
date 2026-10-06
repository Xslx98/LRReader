package com.lanraragi.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * Restarts the app process ("rebirth") through [PhoenixActivity], the
 * ProcessPhoenix pattern (audit 2026-10-06b STAB-10).
 *
 * The old path scheduled an inexact RTC alarm with an activity PendingIntent
 * and killed the process. The alarm fires after the process is dead, so on
 * API 34+ the background-activity-launch rules block it (observed on an API 36
 * AVD: the app did not come back after "Reset saved credentials"), and an
 * inexact alarm can also land seconds late on top of a running activity.
 *
 * Here the still-foreground app starts [PhoenixActivity] in its own process
 * (`:phoenix`). That activity kills this process, starts the launch intent
 * with NEW_TASK|CLEAR_TASK (which also drops the dead process's activity
 * records, so no stale scene stack is restored) and then exits.
 */
object ProcessRebirth {

    private const val TAG = "ProcessRebirth"

    /** Suffix of the `android:process` [PhoenixActivity] runs in. */
    const val PROCESS_SUFFIX = ":phoenix"

    internal const val EXTRA_MAIN_PID = "com.lanraragi.reader.extra.REBIRTH_MAIN_PID"
    internal const val EXTRA_LAUNCH_INTENT = "com.lanraragi.reader.extra.REBIRTH_LAUNCH_INTENT"

    /**
     * Safety net: if the phoenix never kills this process (it crashed, or the
     * start was dropped because the app was already in the background), the
     * process still dies, as the old path always did.
     */
    internal const val FALLBACK_KILL_DELAY_MS = 5_000L

    /** True for the `:phoenix` process, which must skip the app's boot work. */
    fun isPhoenixProcess(processName: String?): Boolean =
        processName?.endsWith(PROCESS_SUFFIX) == true

    /**
     * Start the phoenix (which kills this process and relaunches the app).
     * Falls back to killing the process right away when the phoenix cannot
     * be started.
     */
    fun trigger(context: Context) {
        val pid = Process.myPid()
        val launch = launchIntent(context)
        val started = launch != null && startPhoenix(context, phoenixIntent(context, launch, pid))
        if (started) {
            Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(pid) }, FALLBACK_KILL_DELAY_MS)
        } else {
            Process.killProcess(pid)
        }
    }

    /** The launcher entry, starting a fresh task in place of the current one. */
    internal fun launchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

    internal fun phoenixIntent(context: Context, launch: Intent, mainPid: Int): Intent =
        Intent(context, PhoenixActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_MAIN_PID, mainPid)
            .putExtra(EXTRA_LAUNCH_INTENT, launch)

    private fun startPhoenix(context: Context, intent: Intent): Boolean =
        try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "phoenix activity missing; killing without relaunch", e)
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "phoenix start refused; killing without relaunch", e)
            false
        }
}
