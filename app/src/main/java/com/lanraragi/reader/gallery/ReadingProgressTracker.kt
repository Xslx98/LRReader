package com.lanraragi.reader.gallery

import com.lanraragi.reader.ServiceRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory reactive layer over the local reading-progress SharedPreferences.
 *
 * The reader persists the current page (0-indexed) per arcid via
 * [GalleryProvider2.saveReadingProgress] as the user reads. Detail-page UI
 * needs to observe these writes so the displayed progress refreshes when the
 * user returns from the reader, without relying on a one-shot `onResume`
 * sync that re-reads SharedPreferences.
 *
 * Each arcid gets a lazily created [MutableStateFlow] seeded from
 * SharedPreferences on first access. [GalleryProvider2.saveReadingProgress]
 * calls [setProgress] after the SP write so observers see the new value
 * immediately — no polling, no stale display.
 *
 * Flows are keyed like the store ([LocalReadingProgress.key]): per server
 * profile and arcid. Keyed by arcid alone, the same content-hash arcid on
 * another server showed the previous server's page in the detail header after
 * a profile switch (audit 2026-10-06 C37). Observers read the active
 * profile's flow; a reader of another server's download writes that server's
 * flow, which the detail header shows once that profile is active.
 */
object ReadingProgressTracker {

    private val flows = ConcurrentHashMap<String, MutableStateFlow<Int>>()

    /** Returns a flow that always reflects the latest 0-indexed local progress for [arcid]. */
    fun progressFlow(arcid: String): StateFlow<Int> =
        flowFor(LocalReadingProgress.profileId(), arcid).asStateFlow()

    /** Notifies observers after [GalleryProvider2.saveReadingProgress] writes SP. */
    @JvmStatic
    fun setProgress(arcid: String, page: Int) {
        setProgress(LocalReadingProgress.profileId(), arcid, page)
    }

    /** As [setProgress] for the save stored under [profileId] (the archive's source profile). */
    internal fun setProgress(profileId: Long, arcid: String, page: Int) {
        flowFor(profileId, arcid).value = page
    }

    /** The local save for [arcid] on [profileId] was removed. */
    internal fun clearProgress(profileId: Long, arcid: String) {
        setProgress(profileId, arcid, NO_LOCAL_PROGRESS)
    }

    /**
     * Sentinel "no local progress yet" value. Returned when the SP has never
     * been written for this arcid (timestamp == 0), so observers can keep
     * trusting the server-reported progress instead of clobbering it with
     * the SP default of 0.
     */
    const val NO_LOCAL_PROGRESS: Int = -1

    private fun flowFor(profileId: Long, arcid: String): MutableStateFlow<Int> =
        flows.getOrPut(LocalReadingProgress.key(profileId, arcid)) {
            // Defensive: ServiceRegistry may not be initialized in unit tests or
            // very early app startup. Fall back to NO_LOCAL_PROGRESS so observers
            // simply defer to whatever progress is already in memory.
            val initial = runCatching {
                val ctx = ServiceRegistry.appModule.getContext()
                val ts = GalleryProvider2.loadReadingTimestamp(ctx, arcid, profileId)
                if (ts > 0) GalleryProvider2.loadReadingProgress(ctx, arcid, profileId) else NO_LOCAL_PROGRESS
            }.getOrDefault(NO_LOCAL_PROGRESS)
            MutableStateFlow(initial)
        }
}
