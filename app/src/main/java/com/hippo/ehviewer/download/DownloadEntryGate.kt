package com.hippo.ehviewer.download

/**
 * Classifies a browse-entry download request (user tapped "download" from a
 * list / detail / multi-select context) against the archive's current
 * download state.
 *
 * [DownloadState.FINISH] rows come back as [Disposition.ALREADY_LOCAL] and
 * must NOT be re-queued from browse entries: arcid is a content hash, so the
 * same archive reached through a different server profile (e.g. the LAN and
 * WAN endpoints of one server) shares the single download row and on-disk
 * dir, and re-queuing runs the worker against the *source* profile's URL —
 * when that URL is unreachable from the current network the re-run flips a
 * completed download to FAILED. Explicit repair/restart from the Downloads
 * scene bypasses this gate on purpose.
 */
object DownloadEntryGate {

    enum class Disposition {
        /** No download row — add and queue as a fresh download. */
        NEW,

        /** Row exists but is not complete — re-queue (WAIT/DOWNLOAD rows no-op downstream). */
        RESTART,

        /** Row is FINISH — already on disk; skip and tell the user instead. */
        ALREADY_LOCAL,
    }

    fun disposition(state: DownloadState): Disposition = when (state) {
        DownloadState.INVALID -> Disposition.NEW
        DownloadState.FINISH -> Disposition.ALREADY_LOCAL
        DownloadState.NONE,
        DownloadState.WAIT,
        DownloadState.DOWNLOAD,
        DownloadState.FAILED,
        -> Disposition.RESTART
    }
}
