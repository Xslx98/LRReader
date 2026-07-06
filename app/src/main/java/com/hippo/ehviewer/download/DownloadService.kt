/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.download

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.IntDef
import androidx.core.app.NotificationCompat
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.ServiceRegistry
import com.lanraragi.reader.domain.Archive
import com.hippo.ehviewer.R
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.util.ReadableTime
import com.hippo.lib.yorozuya.FileUtils
import com.hippo.lib.yorozuya.collect.SparseJBArray
import com.hippo.lib.yorozuya.collect.SparseJLArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("UnspecifiedImmutableFlag")
class DownloadService : Service(), DownloadListener {
    private val TAG = "DownloadService"
    private var mNotifyManager: NotificationManager? = null
    private var mDownloadManager: DownloadManager? = null
    private var mDownloadingBuilder: NotificationCompat.Builder? = null
    private var mDownloadedBuilder: NotificationCompat.Builder? = null
    private var m509dBuilder: NotificationCompat.Builder? = null
    private var mDownloadingDelay: NotificationDelay? = null
    private var mDownloadedDelay: NotificationDelay? = null
    private var m509Delay: NotificationDelay? = null

    /**
     * Holds a CPU + Wi-Fi lock for the duration of a download session so
     * downloads keep running when the screen is off. The foreground service
     * alone only keeps the *process* alive, not the CPU/radio. Bracketed by
     * onCreate/onDestroy, which the service scopes to "has download work".
     */
    private var mKeepAlive: DownloadKeepAlive? = null

    // Last-emitted (arcid, finished) for the in-progress notification. The
    // AOSP DownloadProvider gates progress notifications on BOTH a 2 s
    // time-delta and a 64 KB byte-delta; we already have the time gate
    // inside NotificationDelay, and this is the progress-delta equivalent
    // (skip the rebuild when no page has finished since the last emit).
    // SpeedTracker drives a periodic onDownload tick — that path will
    // refresh the speed text on the next page completion.
    private var mLastNotifiedArcid: String? = null
    private var mLastNotifiedFinished: Int = -1

    /** Arcids currently paused waiting for the network. While an arcid is in
     *  this set its own onUpdate rebuilds are suppressed so the "Waiting for
     *  network…" notification is not clobbered by speed-tracker ticks — but a
     *  different, still-downloading archive can keep updating the notification
     *  (a single global flag froze every concurrent download instead). All
     *  access is on the main thread. */
    private val mPausedArcids = HashSet<String>()

    /**
     * Service-scoped CoroutineScope for background awaits (notably
     * [DownloadManager.awaitInitAsync]). Cancelled in [onDestroy] to clean
     * up any in-flight init wait. Runs on [Dispatchers.IO] so the await
     * does not pin the main thread.
     */
    private val serviceScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.IO +
            ServiceRegistry.coroutineModule.exceptionHandler
    )

    private val cachedLargeIcon: Bitmap? by lazy {
        BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
    }

    private var CHANNEL_ID: String? = null
    private var COMPLETE_CHANNEL_ID: String? = null

    /**
     * True once this service instance has entered the foreground. Lets
     * onStartCommand skip the placeholder notification on later commands —
     * re-posting it over the live progress notification caused a visible
     * flicker on every START/STOP command.
     */
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()

        CHANNEL_ID = "$packageName.download"
        COMPLETE_CHANNEL_ID = "$packageName.download.complete"
        mNotifyManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Ongoing progress (foreground service) stays IMPORTANCE_LOW — a
            // persistent bar should be silent and non-intrusive.
            mNotifyManager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, getString(R.string.download_service),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            // Terminal events (download finished / 509 rate-limited) go on a
            // separate IMPORTANCE_DEFAULT channel so they get a sound and a
            // heads-up instead of silently joining the LOW progress channel.
            mNotifyManager?.createNotificationChannel(
                NotificationChannel(
                    COMPLETE_CHANNEL_ID, getString(R.string.stat_download_done_title),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        mDownloadManager = ServiceRegistry.dataModule.downloadManager
        mDownloadManager?.setDownloadListener(this)

        // Keep the CPU + Wi-Fi awake for as long as this service lives. The
        // service only exists while there is download work (it stopSelf's when
        // the scheduler reports idle), so onCreate→onDestroy brackets exactly
        // one download session. Without this the download stalls the instant
        // the screen turns off, even though the foreground notification stays up.
        mKeepAlive = DownloadKeepAlive(this).also { it.acquire() }
    }

    override fun onDestroy() {
        super.onDestroy()

        serviceScope.cancel()
        mKeepAlive?.release()
        mKeepAlive = null
        mNotifyManager = null
        mDownloadManager?.setDownloadListener(null)
        mDownloadManager = null
        mDownloadingBuilder = null
        mDownloadedBuilder = null
        m509dBuilder = null
        mDownloadingDelay?.release()
        mDownloadedDelay?.release()
        m509Delay?.release()
    }

    /**
     * Handle a start command.
     *
     * Foreground services must call [Service.startForeground] within 5 seconds
     * of being started or Android will ANR/kill the process. On a cold launch,
     * [DownloadManager] has not finished its async DB load yet, and the shared
     * mutable collections that [handleIntent] reaches into (via
     * [DownloadManager.deleteDownload], [DownloadManager.startDownload], etc.)
     * are only safe to touch after [DownloadManager.awaitInitAsync] resumes.
     *
     * To keep both invariants we:
     *   1. Call [startForeground] immediately with a placeholder notification
     *      so the 5-second window is satisfied regardless of how slow init is.
     *   2. Launch a coroutine on [serviceScope] that awaits init and then
     *      dispatches the intent on the main thread (the [DownloadManager]
     *      mutators are all `assertMainThread`-guarded).
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Satisfy the foreground-service ANR window before anything else.
        //    Skip if we're already foreground — re-posting the placeholder over
        //    the live progress notification flickers it on every command.
        if (!isForeground) {
            startForegroundPlaceholder()
        }

        // 2. Await init off the main thread, then hand the intent back to
        //    main-thread handleIntent which is where the DownloadManager
        //    mutators live.
        val dm = mDownloadManager
        if (intent != null) {
            serviceScope.launch {
                try {
                    dm?.awaitInitAsync()
                } catch (e: Exception) {
                    Log.e(TAG, "awaitInitAsync failed; processing intent anyway", e)
                }
                withContext(Dispatchers.Main) {
                    try {
                        handleIntent(intent)
                    } catch (e: NullPointerException) {
                        Log.e(TAG, "Unexpected NPE in handleIntent — intent=$intent", e)
                    }
                }
            }
        } else {
            // START_STICKY restart with a null intent: the in-memory download queue did
            // not survive process death, so there is nothing to resume. Await init then
            // stop, otherwise the placeholder foreground notification and the CPU/Wi-Fi
            // wakelocks (acquired in onCreate) would be held indefinitely.
            serviceScope.launch {
                try {
                    dm?.awaitInitAsync()
                } catch (e: Exception) {
                    Log.e(TAG, "awaitInitAsync failed on null-intent restart", e)
                }
                withContext(Dispatchers.Main) { checkStopSelf() }
            }
        }
        return START_STICKY
    }

    /**
     * Show a placeholder foreground notification reusing the downloading
     * notification channel / id so that the real progress notification (set
     * up lazily by [ensureDownloadingBuilder] on the first [onStart] callback)
     * can simply replace it without a channel or id collision.
     */
    private fun startForegroundPlaceholder() {
        val channelId = CHANNEL_ID ?: return
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.download_service))
            .setContentText(getString(R.string.please_wait))
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .setShowWhen(false)
            .setChannelId(channelId)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    ID_DOWNLOADING,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(ID_DOWNLOADING, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground(placeholder) failed", e)
        }
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.action
        if (action == null) {
            checkStopSelf()
            return
        }
        val dm = mDownloadManager
        when (action) {
            ACTION_CLEAR -> clear()
            ACTION_DELETE_RANGE -> {
                val arcidList = intent.getStringArrayListExtra(KEY_ARCID_LIST)
                if (arcidList != null && dm != null) {
                    dm.deleteRangeDownload(arcidList)
                }
            }

            ACTION_DELETE -> {
                val arcid = intent.getStringExtra(KEY_ARCID)
                if (arcid != null && dm != null) {
                    dm.deleteDownload(arcid)
                }
            }

            ACTION_STOP_ALL -> dm?.stopAllDownload()

            ACTION_STOP_RANGE -> {
                val arcidListS = intent.getStringArrayListExtra(KEY_ARCID_LIST)
                if (arcidListS != null && dm != null) {
                    dm.stopRangeDownload(arcidListS)
                }
            }

            ACTION_STOP_CURRENT -> dm?.stopCurrentDownload()

            ACTION_STOP -> {
                val arcidS = intent.getStringExtra(KEY_ARCID)
                if (arcidS != null && dm != null) {
                    dm.stopDownload(arcidS)
                }
            }

            ACTION_START_ALL -> dm?.startAllDownload()

            ACTION_START_RANGE -> {
                val arcidListSR = intent.getStringArrayListExtra(KEY_ARCID_LIST)
                if (arcidListSR != null && dm != null) {
                    dm.startRangeDownload(arcidListSR)
                }
            }

            ACTION_START -> {
                val archive = intent.getParcelableExtra<Archive>(KEY_ARCHIVE)
                val label = intent.getStringExtra(KEY_LABEL)
                if (archive != null && dm != null) {
                    dm.startDownload(archive, label)
                }
            }
        }
        checkStopSelf()
    }

    override fun onBind(intent: Intent): IBinder? {
        throw IllegalStateException("No bindService")
    }

    @Suppress("deprecation")
    private fun ensureDownloadingBuilder() {
        if (mDownloadingBuilder != null) {
            return
        }

        val stopAllIntent = Intent(this, DownloadService::class.java)
        stopAllIntent.setAction(ACTION_STOP_ALL)
        val piStopAll = PendingIntent.getService(this, 0, stopAllIntent, PendingIntent.FLAG_IMMUTABLE)

        val channelId = CHANNEL_ID ?: return
        mDownloadingBuilder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setColor(resources.getColor(R.color.colorPrimary))
            .addAction(
                R.drawable.ic_pause_x24,
                getString(R.string.stat_download_action_stop_all),
                piStopAll
            )
            .setShowWhen(false)
            .setChannelId(channelId)

        val builder = mDownloadingBuilder ?: return
        mDownloadingDelay =
            NotificationDelay(this, mNotifyManager, builder, ID_DOWNLOADING)
    }

    private fun ensureDownloadedBuilder() {
        if (mDownloadedBuilder != null) {
            return
        }

        val clearIntent = Intent(this, DownloadService::class.java)
        clearIntent.setAction(ACTION_CLEAR)
        val piClear = PendingIntent.getService(this, 0, clearIntent, PendingIntent.FLAG_IMMUTABLE)

        val bundle = Bundle()
        bundle.putString(SCENE_KEY_ACTION, SCENE_ACTION_CLEAR)
        val activityIntent = Intent().setClassName(packageName, TARGET_ACTIVITY)
        activityIntent.setAction(ACTION_START_SCENE)
        activityIntent.putExtra(KEY_SCENE_NAME, TARGET_SCENE)
        activityIntent.putExtra(KEY_SCENE_ARGS, bundle)
        val piActivity = PendingIntent.getActivity(
            this@DownloadService, 0,
            activityIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = COMPLETE_CHANNEL_ID ?: return
        mDownloadedBuilder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setLargeIcon(cachedLargeIcon)
            .setContentTitle(getString(R.string.stat_download_done_title))
            .setDeleteIntent(piClear)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(piActivity)
            .setChannelId(channelId)

        val builder = mDownloadedBuilder ?: return
        mDownloadedDelay =
            NotificationDelay(this, mNotifyManager, builder, ID_DOWNLOADED)
    }

    private fun ensure509Builder() {
        if (m509dBuilder != null) {
            return
        }

        val channelId = COMPLETE_CHANNEL_ID ?: return
        m509dBuilder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setLargeIcon(cachedLargeIcon)
            .setContentTitle(getString(R.string.stat_509_alert_title))
            .setContentText(getString(R.string.stat_509_alert_text))
            .setAutoCancel(true)
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setChannelId(channelId)

        val builder = m509dBuilder ?: return
        m509Delay = NotificationDelay(this, mNotifyManager, builder, ID_509)
    }

    override fun onGet509() {
        if (mNotifyManager == null) {
            return
        }

        ensure509Builder()
        val builder509 = m509dBuilder ?: return
        builder509.setWhen(System.currentTimeMillis())
        m509Delay?.show()
    }

    override fun onStart(info: DownloadInfo) {
        if (mNotifyManager == null) {
            return
        }

        // Reset the progress-delta gate so the first onUpdate for this task
        // always fires (even if a prior task happened to leave finished at
        // the same numeric value).
        mLastNotifiedArcid = null
        mLastNotifiedFinished = -1
        mPausedArcids.remove(info.arcid)

        ensureDownloadingBuilder()

        val bundle = Bundle()
        bundle.putString(SCENE_KEY_ARCID, info.arcid)
        val activityIntent = Intent().setClassName(packageName, TARGET_ACTIVITY)
        activityIntent.setAction(ACTION_START_SCENE)
        activityIntent.putExtra(KEY_SCENE_NAME, TARGET_SCENE)
        activityIntent.putExtra(KEY_SCENE_ARGS, bundle)
        val piActivity = PendingIntent.getActivity(
            this@DownloadService, 0,
            activityIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dlBuilder = mDownloadingBuilder ?: return
        dlBuilder.setContentTitle(info.title)
            .setContentText(null)
            .setContentInfo(null)
            .setProgress(0, 0, true)
            .setContentIntent(piActivity)

        mDownloadingDelay?.startForeground()
    }

    private fun onUpdate(info: DownloadInfo) {
        if (mNotifyManager == null) {
            return
        }
        if (info.arcid in mPausedArcids) return
        ensureDownloadingBuilder()

        val snap = mDownloadManager?.progressFor(info.arcid)
        val finished = snap?.finished ?: -1

        // Progress-delta gate (paired with NotificationDelay's 2 s time gate
        // for the AOSP "both must pass" behaviour). When the same archive is
        // ticking but no page has completed, skip the rebuild + IPC entirely
        // — there is nothing user-visible to redraw, and a flood of these
        // calls is what drove NotificationManagerService into Shedding mode.
        if (info.arcid == mLastNotifiedArcid && finished == mLastNotifiedFinished) {
            return
        }
        mLastNotifiedArcid = info.arcid
        mLastNotifiedFinished = finished

        var speed = snap?.speed ?: -1L
        if (speed < 0) {
            speed = 0
        }
        var text = FileUtils.humanReadableByteCount(speed, false) + "/S"
        val remaining = snap?.remaining ?: -1L
        text = if (remaining >= 0) {
            getString(
                R.string.download_speed_text_2,
                text,
                ReadableTime.getShortTimeInterval(remaining)
            )
        } else {
            getString(R.string.download_speed_text, text)
        }
        val total = snap?.total ?: -1
        val dlBuilder = mDownloadingBuilder ?: return
        dlBuilder.setContentTitle(info.title)
            .setContentText(text)
            .setContentInfo(if (total == -1 || finished == -1) null else "$finished/$total")
            .setProgress(total, finished, false)

        mDownloadingDelay?.startForeground()
    }

    override fun onDownload(info: DownloadInfo) {
        onUpdate(info)
    }

    override fun onGetPage(info: DownloadInfo) {
        onUpdate(info)
    }

    override fun onFinish(info: DownloadInfo) {
        if (mNotifyManager == null) {
            return
        }
        mPausedArcids.remove(info.arcid)

        // The active task is gone; clear the progress-delta gate so the next
        // task's first onUpdate fires unconditionally (see onStart).
        if (info.arcid == mLastNotifiedArcid) {
            mLastNotifiedArcid = null
            mLastNotifiedFinished = -1
        }

        if (null != mDownloadingDelay) {
            mDownloadingDelay?.cancel()
        }

        ensureDownloadedBuilder()

        val finish = info.state == DownloadState.FINISH
        val arcid = info.arcid
        val previous = sItemStateArray[arcid]
        if (previous == null) { // Not contain
            sItemStateArray[arcid] = finish
            sItemTitleArray[arcid] = info.title
            sDownloadedCount++
            if (finish) {
                sFinishedCount++
            } else {
                sFailedCount++
            }
        } else { // Contain
            sItemStateArray[arcid] = finish
            sItemTitleArray[arcid] = info.title
            if (previous && !finish) {
                sFinishedCount--
                sFailedCount++
            } else if (!previous && finish) {
                sFinishedCount++
                sFailedCount--
            }
        }

        val text: String
        val needStyle: Boolean
        if (sFinishedCount != 0 && sFailedCount == 0) {
            if (sFinishedCount == 1) {
                val firstTitle = sItemTitleArray.values.firstOrNull()
                if (firstTitle != null) {
                    text = getString(
                        R.string.stat_download_done_line_succeeded,
                        firstTitle
                    )
                } else {
                    Log.d("TAG", "WTF, sItemTitleArray is null")
                    text = getString(R.string.error_unknown)
                }
                needStyle = false
            } else {
                text = resources.getQuantityString(
                    R.plurals.stat_download_done_text_succeeded, sFinishedCount, sFinishedCount
                )
                needStyle = true
            }
        } else if (sFinishedCount == 0 && sFailedCount != 0) {
            if (sFailedCount == 1) {
                val firstTitle = sItemTitleArray.values.firstOrNull()
                if (firstTitle != null) {
                    text = getString(
                        R.string.stat_download_done_line_failed,
                        firstTitle
                    )
                } else {
                    Log.d("TAG", "WTF, sItemTitleArray is null")
                    text = getString(R.string.error_unknown)
                }
                needStyle = false
            } else {
                text = getString(R.string.stat_download_done_text_failed, sFailedCount)
                needStyle = true
            }
        } else {
            text = getString(R.string.stat_download_done_text_mix, sFinishedCount, sFailedCount)
            needStyle = true
        }

        val style: NotificationCompat.InboxStyle?
        if (needStyle) {
            style = NotificationCompat.InboxStyle()
            style.setBigContentTitle(getString(R.string.stat_download_done_title))
            for ((arcid, fin) in sItemStateArray) {
                val title = sItemTitleArray[arcid] ?: continue
                style.addLine(
                    getString(
                        if (fin) R.string.stat_download_done_line_succeeded else R.string.stat_download_done_line_failed,
                        title
                    )
                )
            }
        } else {
            style = null
        }

        val doneBuilder = mDownloadedBuilder ?: return
        doneBuilder.setContentText(text)
            .setStyle(style)
            .setWhen(System.currentTimeMillis())
            .setNumber(sDownloadedCount)

        mDownloadedDelay?.show()

        checkStopSelf()
    }

    override fun onCancel(info: DownloadInfo) {
        if (mNotifyManager == null) {
            return
        }

        if (null != mDownloadingDelay) {
            mDownloadingDelay?.cancel()
        }

        checkStopSelf()
    }

    override fun onNetworkWait(info: DownloadInfo, waiting: Boolean) {
        if (mNotifyManager == null) return
        if (waiting) {
            mPausedArcids.add(info.arcid)
            ensureDownloadingBuilder()
            val dlBuilder = mDownloadingBuilder ?: return
            dlBuilder.setContentTitle(info.title)
                .setContentText(getString(R.string.download_waiting_for_network))
                .setContentInfo(null)
                .setProgress(0, 0, true)
            mDownloadingDelay?.startForeground()
        } else {
            // Resumed: clear this arcid's pause guard and force the next onUpdate
            // to redraw real progress (it is gated on a finished-count delta
            // which a resume would otherwise suppress).
            mPausedArcids.remove(info.arcid)
            mLastNotifiedArcid = null
            mLastNotifiedFinished = -1
        }
    }

    private fun checkStopSelf() {
        if (mDownloadManager?.isIdle != false) {
//            stopForeground(true);
            stopSelf()
        }
    }

    // KNOWN-ISSUE (P2): notifications should be batched into a single delayed update
    // Avoid frequent notification
    private class NotificationDelay(
        private var mService: Service?, private val mNotifyManager: NotificationManager?,
        private val mBuilder: NotificationCompat.Builder, private val mId: Int
    ) : Runnable {
        @IntDef(OPS_NOTIFY, OPS_CANCEL, OPS_START_FOREGROUND)
        @Retention(AnnotationRetention.SOURCE)
        private annotation class Ops

        private val handler = Handler(Looper.getMainLooper())
        private var mLastTime: Long = 0
        private var mPosted = false

        // false for show, true for cancel
        @Ops
        private var mOps = 0

        fun release() {
            mService = null
        }

        fun show() {
            if (mPosted) {
                mOps = OPS_NOTIFY
            } else {
                val now = SystemClock.uptimeMillis()
                if (now - mLastTime > DELAY) {
                    // Wait long enough, do it now
                    mNotifyManager?.notify(mId, mBuilder.build())
                } else {
                    // Too quick, post delay
                    mOps = OPS_NOTIFY
                    mPosted = true
                    handler.postDelayed(this, DELAY)
                }
                mLastTime = now
            }
        }

        fun cancel() {
            if (mPosted) {
                mOps = OPS_CANCEL
            } else {
                val now = SystemClock.uptimeMillis()
                if (now - mLastTime > DELAY) {
                    // Wait long enough, do it now
                    mNotifyManager?.cancel(mId)
                } else {
                    // Too quick, post delay
                    mOps = OPS_CANCEL
                    mPosted = true
                    handler.postDelayed(this, DELAY)
                }
                mLastTime = now
            }
        }

        fun startForeground() {
            if (mPosted) {
                mOps = OPS_START_FOREGROUND
            } else {
                val now = SystemClock.uptimeMillis()
                if (now - mLastTime > DELAY) {
                    // Wait long enough, do it now
                    if (mService != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            mService?.startForeground(
                                mId,
                                mBuilder.build(),
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                            )
                        } else {
                            mService?.startForeground(mId, mBuilder.build())
                        }
                    }
                } else {
                    // Too quick, post delay
                    mOps = OPS_START_FOREGROUND
                    mPosted = true
                    handler.postDelayed(this, DELAY)
                }
                mLastTime = now
            }
        }

        override fun run() {
            mPosted = false
            when (mOps) {
                OPS_NOTIFY -> mNotifyManager?.notify(mId, mBuilder.build())
                OPS_CANCEL -> mNotifyManager?.cancel(mId)
                OPS_START_FOREGROUND -> if (mService != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        mService?.startForeground(
                            mId,
                            mBuilder.build(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                    } else {
                        mService?.startForeground(mId, mBuilder.build())
                    }
                }
            }
        }

        companion object {
            private const val OPS_NOTIFY = 0
            private const val OPS_CANCEL = 1
            private const val OPS_START_FOREGROUND = 2

            // 2 s matches AOSP DownloadProvider's MIN_PROGRESS_TIME and stays
            // safely under NotificationManagerService's 10 enqueues/s/package
            // ceiling (and the ≤5/s soft target Google notifications engineers
            // recommend). Combined with the per-finished gate in onUpdate,
            // this implements the AOSP "both time AND progress delta must
            // pass" pattern.
            private const val DELAY: Long = 2000
        }
    }

    companion object {
        const val ACTION_START: String = "start"
        const val ACTION_START_RANGE: String = "start_range"
        const val ACTION_START_ALL: String = "start_all"
        const val ACTION_STOP: String = "stop"
        const val ACTION_STOP_RANGE: String = "stop_range"
        const val ACTION_STOP_CURRENT: String = "stop_current"
        const val ACTION_STOP_ALL: String = "stop_all"
        const val ACTION_DELETE: String = "delete"
        const val ACTION_DELETE_RANGE: String = "delete_range"
        const val ACTION_CLEAR: String = "clear"

        const val KEY_ARCHIVE: String = "archive"
        const val KEY_LABEL: String = "label"
        const val KEY_ARCID: String = "arcid"
        const val KEY_ARCID_LIST: String = "arcid_list"

        private const val ID_DOWNLOADING = 1
        private const val ID_DOWNLOADED = 2
        private const val ID_509 = 3

        // Intent targets — string constants to avoid importing UI layer classes.
        // Values must match the actual constants in the respective UI classes.
        private const val TARGET_ACTIVITY = "com.hippo.ehviewer.ui.MainActivity"
        private const val ACTION_START_SCENE = "start_scene"
        private const val KEY_SCENE_NAME = "stage_activity_scene_name"
        private const val KEY_SCENE_ARGS = "stage_activity_scene_args"
        private const val TARGET_SCENE = "com.hippo.ehviewer.ui.scene.download.DownloadsScene"
        private const val SCENE_KEY_ACTION = "action"
        private const val SCENE_ACTION_CLEAR = "clear_download_service"
        private const val SCENE_KEY_ARCID = "arcid"

        // Keyed by arcid post-W36-10 (gid column dropped from DOWNLOADS).
        private val sItemStateArray = LinkedHashMap<String, Boolean>()
        private val sItemTitleArray = LinkedHashMap<String, String?>()

        private var sFailedCount = 0
        private var sFinishedCount = 0
        private var sDownloadedCount = 0

        fun clear() {
            sFailedCount = 0
            sFinishedCount = 0
            sDownloadedCount = 0
            sItemStateArray.clear()
            sItemTitleArray.clear()
        }
    }
}
