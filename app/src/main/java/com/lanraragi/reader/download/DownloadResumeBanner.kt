package com.lanraragi.reader.download

/**
 * Process-wide record of downloads that paused waiting for the network or gave
 * up after the wait timeout, surfaced to the user as an in-app Snackbar the next
 * time the app returns to the foreground (consumed by MainActivity.onStart).
 *
 * Mirrors the AppLockGate pattern: set from the download scheduler on the main
 * thread, consumed by the UI. Methods are synchronized defensively.
 *
 * Downloads interrupted by process death (A47) are the exception to
 * "process-wide": they are recorded while the next process loads the download
 * list, which may itself die before the user returns, so they live in
 * [interruptedStore] rather than in memory.
 */
object DownloadResumeBanner {

    /** Where the interrupted-download arcids are kept between processes. */
    interface InterruptedStore {
        fun load(): Set<String>
        fun save(arcids: Set<String>)
    }

    private class MemoryStore : InterruptedStore {
        private var arcids: Set<String> = emptySet()
        override fun load(): Set<String> = arcids
        override fun save(arcids: Set<String>) { this.arcids = arcids.toSet() }
    }

    /** Process memory by default; the application installs a persistent store at boot. */
    @Volatile
    var interruptedStore: InterruptedStore = MemoryStore()

    sealed interface Snapshot {
        data object None : Snapshot
        /** One or more downloads are paused waiting for the network. */
        data class Paused(val count: Int) : Snapshot
        /** Downloads gave up after the wait timeout; [arcids] can be re-queued. */
        data class TimedOut(val arcids: List<String>, val count: Int) : Snapshot
        /** Queued or running downloads were reset by process death; [arcids] can be re-queued. */
        data class Interrupted(val arcids: List<String>, val count: Int) : Snapshot
        /** The disk filled up; these downloads were paused and can be re-queued once space is freed. */
        data class StorageFull(val arcids: List<String>, val count: Int) : Snapshot
        /** The system refused the foreground service; these downloads were paused (audit C34). */
        data class SystemLimited(val arcids: List<String>, val count: Int) : Snapshot
    }

    private val paused = LinkedHashMap<String, String>()   // arcid -> title
    private val timedOut = LinkedHashMap<String, String>() // arcid -> title
    private val storageFull = LinkedHashMap<String, String>() // arcid -> title
    private val systemLimited = LinkedHashMap<String, String>() // arcid -> title

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
        storageFull.remove(arcid)
        systemLimited.remove(arcid)
        val interrupted = interruptedStore.load()
        if (arcid in interrupted) interruptedStore.save(interrupted - arcid)
    }

    /** Record downloads the boot-time reset took out of the queue. */
    @Synchronized
    fun markInterrupted(arcids: Collection<String>) {
        if (arcids.isEmpty()) return
        interruptedStore.save(interruptedStore.load() + arcids)
    }

    /** Record a download paused because the disk is full (audit C21). */
    @Synchronized
    fun markStorageFull(arcid: String, title: String?) {
        paused.remove(arcid)
        timedOut.remove(arcid)
        storageFull[arcid] = title ?: arcid
    }

    /** Record a download paused because the system refused the foreground service (audit C34). */
    @Synchronized
    fun markSystemLimited(arcid: String, title: String?) {
        paused.remove(arcid)
        timedOut.remove(arcid)
        systemLimited[arcid] = title ?: arcid
    }

    @Synchronized
    fun markTimedOut(arcid: String, title: String?) {
        paused.remove(arcid)
        timedOut[arcid] = title ?: arcid
    }

    /**
     * Read the current state and clear it (one-shot). Precedence: StorageFull (the
     * user must act first), then SystemLimited, then TimedOut, then Interrupted, then Paused. Interrupted downloads not shown this time are
     * kept for the next foreground.
     */
    @Synchronized
    fun consume(): Snapshot {
        val interrupted = interruptedStore.load()
        val snapshot = when {
            storageFull.isNotEmpty() -> Snapshot.StorageFull(storageFull.keys.toList(), storageFull.size)
            systemLimited.isNotEmpty() -> Snapshot.SystemLimited(systemLimited.keys.toList(), systemLimited.size)
            timedOut.isNotEmpty() -> Snapshot.TimedOut(timedOut.keys.toList(), timedOut.size)
            interrupted.isNotEmpty() -> {
                interruptedStore.save(emptySet())
                Snapshot.Interrupted(interrupted.toList(), interrupted.size)
            }
            paused.isNotEmpty() -> Snapshot.Paused(paused.size)
            else -> Snapshot.None
        }
        paused.clear()
        timedOut.clear()
        storageFull.clear()
        systemLimited.clear()
        return snapshot
    }

    @Synchronized
    fun clear() {
        paused.clear()
        timedOut.clear()
        storageFull.clear()
        systemLimited.clear()
        interruptedStore.save(emptySet())
    }
}
