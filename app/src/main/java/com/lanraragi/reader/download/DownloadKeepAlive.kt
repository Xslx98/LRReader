package com.lanraragi.reader.download

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

/**
 * Keeps the CPU and Wi-Fi radio awake while a download session is in progress.
 *
 * A foreground service (which [DownloadService] is) stops the app **process**
 * from being killed while backgrounded, but it does **not** stop the device
 * from suspending the CPU (deep sleep / Doze) or the Wi-Fi radio from dropping
 * to power-save once the screen turns off. Without these locks an active
 * download silently stalls the moment the screen locks and only resumes — or
 * fails after a socket timeout — when the screen comes back on. That is the
 * "downloads pause when I lock the screen" symptom.
 *
 * [DownloadService] holds this for the lifetime of a download session: it
 * acquires in `onCreate` and releases in `onDestroy`. The service already
 * lives exactly while there is download work (it `stopSelf`s as soon as the
 * scheduler reports idle), so that bracket maps cleanly onto "downloads are
 * running". Within the session it lets go while every active download is
 * waiting for the network and takes them again on resume ([shouldHold]).
 *
 * Both locks are **non-reference-counted**; [acquire]/[release] are idempotent
 * and `isHeld`-guarded, so repeated calls are safe. Locks are process-scoped:
 * if the process is killed without `onDestroy` the OS reclaims them, so there
 * is no battery-leak risk from a missed release.
 */
class DownloadKeepAlive(context: Context) {

    private val appContext = context.applicationContext

    private val wakeLock: PowerManager.WakeLock? =
        (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
            ?.apply { setReferenceCounted(false) }

    // WIFI_MODE_FULL_HIGH_PERF is deprecated since API 29 in favour of
    // FULL_LOW_LATENCY, but the low-latency mode targets real-time gaming and
    // trades throughput/battery for latency — wrong for a bulk download. The
    // high-perf mode still works on every supported API and is what AOSP's own
    // DownloadManager uses, so we keep it deliberately.
    @Suppress("DEPRECATION")
    private val wifiLock: WifiManager.WifiLock? =
        (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_TAG)
            ?.apply { setReferenceCounted(false) }

    /**
     * Acquire the CPU + Wi-Fi locks. No timeout is set deliberately: the lock
     * is strictly bracketed by the foreground service's lifetime, which ends
     * (→ [release]) the moment downloads finish, and the lock dies with the
     * process if we are killed first. A timeout would re-introduce the very
     * stall this class exists to prevent for any download that outlives it.
     */
    @SuppressLint("WakelockTimeout")
    fun acquire() {
        try {
            wakeLock?.takeIf { !it.isHeld }?.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock", e)
        }
        try {
            wifiLock?.takeIf { !it.isHeld }?.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wifi lock", e)
        }
    }

    /** Release both locks. Idempotent — safe to call without a prior [acquire]. */
    fun release() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wake lock", e)
        }
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wifi lock", e)
        }
    }

    /** True while either lock is currently held. */
    val isHeld: Boolean
        get() = wakeLock?.isHeld == true || wifiLock?.isHeld == true

    companion object {
        /**
         * Whether the locks should be held for [active] downloads, of which
         * [waitingForNetwork] are suspended until the network comes back. A
         * download waiting for the network needs no CPU or radio — it resumes
         * on a connectivity callback — so when every active download waits,
         * holding the locks only drains the battery, for up to the resume
         * timeout per download or for good with "never" (audit PERF-06 /
         * STAB-06). An empty [active] set (between two downloads, or just
         * started) keeps them: the service stops as soon as it is idle.
         */
        fun shouldHold(active: Collection<String>, waitingForNetwork: Set<String>): Boolean =
            active.isEmpty() || active.any { it !in waitingForNetwork }

        private const val TAG = "DownloadKeepAlive"
        private const val WAKE_TAG = "LRReader:download"
        private const val WIFI_TAG = "LRReader:download-wifi"
    }
}
