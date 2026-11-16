package com.lanraragi.reader.download

import android.util.Log
import com.lanraragi.reader.BuildConfig
import com.lanraragi.reader.dao.DownloadFailureReason
import com.lanraragi.reader.dao.DownloadInfo
import com.lanraragi.reader.dao.DownloadState

/**
 * The legal moves of a download that is already in the download graph.
 * [DownloadInfo.transition] is the only writer of a live download's
 * [DownloadInfo.state], [DownloadInfo.failureReason] and remaining-page count
 * ([DownloadInfo.legacy]); rows read from Room and rows being created set
 * their first state directly.
 *
 * ```
 * NONE / FAILED / FINISH / INVALID ──start──▶ WAIT ──promote──▶ DOWNLOAD ──finish──▶ FINISH | FAILED
 *                                    └──────stop──────▶ NONE ◀──stop──────┘
 * ```
 */
internal object DownloadTransitions {

    private const val TAG = "DownloadTransitions"

    /** For each target state, the states a download may come from. */
    private val FROM: Map<DownloadState, Set<DownloadState>> = mapOf(
        // INVALID: a row whose stored code this version does not know; it can be re-queued.
        DownloadState.WAIT to setOf(DownloadState.NONE, DownloadState.FAILED, DownloadState.FINISH, DownloadState.INVALID),
        DownloadState.DOWNLOAD to setOf(DownloadState.WAIT),
        DownloadState.NONE to setOf(DownloadState.WAIT, DownloadState.DOWNLOAD),
        DownloadState.FINISH to setOf(DownloadState.DOWNLOAD),
        DownloadState.FAILED to setOf(DownloadState.DOWNLOAD),
    )

    /** INVALID is never a target: it only names a code that could not be read. */
    fun isLegal(from: DownloadState, to: DownloadState): Boolean = FROM[to]?.contains(from) == true

    /**
     * An illegal move is a bug in the caller: debug builds (and tests) fail
     * at once; a release build logs it and applies the move, as before.
     */
    fun reportIllegal(info: DownloadInfo, to: DownloadState) {
        val message = "Illegal download transition ${info.state} -> $to"
        check(!BuildConfig.DEBUG) { message }
        Log.e(TAG, message)
    }
}

/**
 * Moves this download to [to]. A failure carries its [reason] and the pages
 * still missing ([remaining]); every other state clears the reason. The
 * remaining-page count is unknown while downloading (-1) and 0 once finished.
 * Persisting and notifying are the caller's (they batch them).
 */
internal fun DownloadInfo.transition(
    to: DownloadState,
    reason: DownloadFailureReason? = null,
    remaining: Int = 0,
) {
    if (!DownloadTransitions.isLegal(state, to)) DownloadTransitions.reportIllegal(this, to)
    state = to
    failureReason = if (to == DownloadState.FAILED) reason else null
    when (to) {
        DownloadState.DOWNLOAD -> legacy = -1
        DownloadState.FINISH -> legacy = 0
        DownloadState.FAILED -> legacy = remaining
        else -> Unit
    }
}

/**
 * How one worker run ended, reported once through
 * [DownloadWorkerListener.onFinish]. A cancelled worker reports nothing.
 */
internal sealed interface WorkerResult {
    val finished: Int
    val downloaded: Int
    val total: Int

    /** Every page is on disk. */
    data class Finished(override val downloaded: Int, override val total: Int) : WorkerResult {
        override val finished: Int get() = total
    }

    /** The run ended with pages missing (or no page list at all, [total] 0). */
    data class Failed(
        val reason: DownloadFailureReason?,
        override val finished: Int = 0,
        override val downloaded: Int = 0,
        override val total: Int = 0,
    ) : WorkerResult {
        val remaining: Int get() = total - finished
    }

    companion object {
        /**
         * The end of a full run. A real LANraragi archive has at least one
         * page, so a zero-page run is a failure, never a finish.
         */
        fun of(finished: Int, downloaded: Int, total: Int, reason: DownloadFailureReason?): WorkerResult =
            if (total > 0 && finished == total) Finished(downloaded, total) else Failed(reason, finished, downloaded, total)
    }
}
