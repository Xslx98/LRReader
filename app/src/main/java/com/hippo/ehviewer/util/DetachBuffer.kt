package com.hippo.ehviewer.util

/**
 * Deliver-or-buffer + drain helper for one-shot events that arrive while a
 * consumer is temporarily unable to handle them — e.g. a Scene whose view is
 * detached under a pushed scene. Items that arrive while not ready are held in
 * arrival order and replayed by [drain] once the consumer is ready again.
 *
 * Not thread-safe: confined to the main thread, like the Scene lifecycle
 * callbacks (`collectFlow*` handlers and `onResume`) that use it.
 */
class DetachBuffer<T> {

    private val pending = mutableListOf<T>()

    /**
     * Deliver [item] via [deliver] now if [ready], otherwise buffer it for the
     * next [drain].
     */
    fun deliverOrBuffer(item: T, ready: Boolean, deliver: (T) -> Unit) {
        if (ready) deliver(item) else pending.add(item)
    }

    /**
     * Replay every buffered item through [deliver] in arrival order, then
     * clear the buffer. The snapshot is taken before delivery so a re-entrant
     * [deliverOrBuffer] during a replay is not drained in the same pass.
     */
    fun drain(deliver: (T) -> Unit) {
        if (pending.isEmpty()) return
        val snapshot = pending.toList()
        pending.clear()
        snapshot.forEach { deliver(it) }
    }
}
