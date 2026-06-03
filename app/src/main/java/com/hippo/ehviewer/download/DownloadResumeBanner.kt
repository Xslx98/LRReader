package com.hippo.ehviewer.download

/**
 * Process-wide record of downloads that paused waiting for the network or gave
 * up after the wait timeout, surfaced to the user as an in-app Snackbar the next
 * time the app returns to the foreground (consumed by MainActivity.onStart).
 *
 * Mirrors the AppLockGate pattern: set from the download scheduler on the main
 * thread, consumed by the UI. Methods are synchronized defensively.
 */
object DownloadResumeBanner {

    sealed interface Snapshot {
        data object None : Snapshot
        /** One or more downloads are paused waiting for the network. */
        data class Paused(val count: Int) : Snapshot
        /** Downloads gave up after the wait timeout; [arcids] can be re-queued. */
        data class TimedOut(val arcids: List<String>, val count: Int) : Snapshot
    }

    private val paused = LinkedHashMap<String, String>()   // arcid -> title
    private val timedOut = LinkedHashMap<String, String>() // arcid -> title

    @Synchronized
    fun markPaused(arcid: String, title: String?) {
        timedOut.remove(arcid)
        paused[arcid] = title ?: arcid
    }

    @Synchronized
    fun markResumed(arcid: String) {
        paused.remove(arcid)
        // Also drop any timed-out entry: this is the "clear the banner for this
        // arcid" call used by network-resume AND by stop/delete paths. Without
        // clearing timedOut too, deleting a download that already gave up left a
        // ghost "N downloads timed out" Snackbar on the next foreground.
        timedOut.remove(arcid)
    }

    @Synchronized
    fun markTimedOut(arcid: String, title: String?) {
        paused.remove(arcid)
        timedOut[arcid] = title ?: arcid
    }

    /** Read the current state and clear it (one-shot). TimedOut takes precedence. */
    @Synchronized
    fun consume(): Snapshot {
        val snapshot = when {
            timedOut.isNotEmpty() -> Snapshot.TimedOut(timedOut.keys.toList(), timedOut.size)
            paused.isNotEmpty() -> Snapshot.Paused(paused.size)
            else -> Snapshot.None
        }
        paused.clear()
        timedOut.clear()
        return snapshot
    }

    @Synchronized
    fun clear() {
        paused.clear()
        timedOut.clear()
    }
}
