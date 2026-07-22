package com.hippo.ehviewer.gallery

import androidx.annotation.VisibleForTesting
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One ended reading-session leg: the stretch of a reader session between the
 * point the position was established (or the previous stop) and a reader stop.
 *
 * [startPage]/[endPage] are 0-based reader indices; page-delta math is the
 * consumer's job (clamp negatives — backward jumps are not "pages read").
 */
data class ReadingSessionEnd(
    val arcid: String,
    val serverProfileId: Long,
    val startPage: Int,
    val endPage: Int,
    val pageCount: Int,
)

/**
 * Process-wide registry for reading-session-end consumers (continue-reading
 * shortcut, daily reading aggregate). Dispatch is synchronous on the caller
 * thread — consumers hop to their own scope for any IO.
 */
object ReadingSessionEvents {

    fun interface Listener {
        fun onSessionEnd(end: ReadingSessionEnd)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun register(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    fun notifySessionEnd(end: ReadingSessionEnd) {
        for (listener in listeners) listener.onSessionEnd(end)
    }

    @VisibleForTesting
    fun clearForTest() {
        listeners.clear()
    }
}
