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

        /**
         * Store [arcids] after a removal. Losing a removal to process death only
         * re-offers an already resumed download, so it need not be durable the
         * way [save] is (audit 2026-10-13 STAB-05).
         */
        fun saveAfterRemoval(arcids: Set<String>) = save(arcids)
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

    /** Why a download is on the banner, for the downloads kept in memory. */
    enum class Reason {
        /** Paused waiting for the network. */
        PAUSED,

        /** Gave up after the network wait (or the system's time budget). */
        TIMED_OUT,

        /** Paused because the disk filled up (audit C21). */
        STORAGE_FULL,

        /** Paused because the system refused the foreground service (audit C34). */
        SYSTEM_LIMITED,
    }

    /**
     * The one record of the in-memory banner: each arcid has at most one
     * reason, the latest. Interrupted downloads (A47) are the exception and
     * live in [interruptedStore], since they must survive the process.
     */
    private val reasons = LinkedHashMap<String, Reason>()

    /**
     * [arcid] is on the banner for [reason], replacing any earlier reason,
     * except that a network pause or time-out never hides a storage-full or
     * system-limit entry: those ask the user to act first.
     */
    @Synchronized
    fun mark(arcid: String, reason: Reason) {
        val current = reasons[arcid]
        if (reason.isNetwork && current != null && !current.isNetwork) return
        reasons.remove(arcid) // re-insert at the end: newest last
        reasons[arcid] = reason
    }

    private val Reason.isNetwork: Boolean get() = this == Reason.PAUSED || this == Reason.TIMED_OUT

    fun markResumed(arcid: String) = markResumed(listOf(arcid))

    /**
     * Clears every banner entry of [arcids] with at most one store write, so a
     * start/stop/delete loop over many rows does not rewrite the prefs file
     * once per row (audit 2026-10-13 STAB-05). This is the "clear the banner
     * for this arcid" call used by network-resume AND by stop/delete paths:
     * deleting a download that already gave up must not leave a ghost
     * "N downloads timed out" Snackbar on the next foreground.
     */
    @Synchronized
    fun markResumed(arcids: Collection<String>) {
        if (arcids.isEmpty()) return
        for (arcid in arcids) reasons.remove(arcid)
        val interrupted = interruptedStore.load()
        if (arcids.any { it in interrupted }) interruptedStore.saveAfterRemoval(interrupted - arcids.toSet())
    }

    /** Record downloads the boot-time reset took out of the queue. */
    @Synchronized
    fun markInterrupted(arcids: Collection<String>) {
        if (arcids.isEmpty()) return
        interruptedStore.save(interruptedStore.load() + arcids)
    }

    /**
     * Read the current state and clear it (one-shot). Precedence: StorageFull (the
     * user must act first), then SystemLimited, then TimedOut, then Interrupted, then Paused. Interrupted downloads not shown this time are
     * kept for the next foreground.
     */
    @Synchronized
    fun consume(): Snapshot {
        val interrupted = interruptedStore.load()
        fun with(reason: Reason) = reasons.filterValues { it == reason }.keys.toList()
        val storageFull = with(Reason.STORAGE_FULL)
        val systemLimited = with(Reason.SYSTEM_LIMITED)
        val timedOut = with(Reason.TIMED_OUT)
        val paused = with(Reason.PAUSED)
        val snapshot = when {
            storageFull.isNotEmpty() -> Snapshot.StorageFull(storageFull, storageFull.size)
            systemLimited.isNotEmpty() -> Snapshot.SystemLimited(systemLimited, systemLimited.size)
            timedOut.isNotEmpty() -> Snapshot.TimedOut(timedOut, timedOut.size)
            interrupted.isNotEmpty() -> {
                interruptedStore.save(emptySet())
                Snapshot.Interrupted(interrupted.toList(), interrupted.size)
            }
            paused.isNotEmpty() -> Snapshot.Paused(paused.size)
            else -> Snapshot.None
        }
        reasons.clear()
        return snapshot
    }

    @Synchronized
    fun clear() {
        reasons.clear()
        interruptedStore.save(emptySet())
    }
}
