package com.hippo.ehviewer.gallery

import com.hippo.ehviewer.ServiceRegistry
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
 */
object ReadingProgressTracker {

    private val flows = ConcurrentHashMap<String, MutableStateFlow<Int>>()

    /** Returns a flow that always reflects the latest 0-indexed local progress for [arcid]. */
    fun progressFlow(arcid: String): StateFlow<Int> = flowFor(arcid).asStateFlow()

    /** Notifies observers after [GalleryProvider2.saveReadingProgress] writes SP. */
    @JvmStatic
    fun setProgress(arcid: String, page: Int) {
        flowFor(arcid).value = page
    }

    /**
     * Sentinel "no local progress yet" value. Returned when the SP has never
     * been written for this arcid (timestamp == 0), so observers can keep
     * trusting the server-reported progress instead of clobbering it with
     * the SP default of 0.
     */
    const val NO_LOCAL_PROGRESS: Int = -1

    private fun flowFor(arcid: String): MutableStateFlow<Int> = flows.getOrPut(arcid) {
        val ctx = ServiceRegistry.appModule.getContext()
        val ts = GalleryProvider2.loadReadingTimestamp(ctx, arcid)
        val initial = if (ts > 0) GalleryProvider2.loadReadingProgress(ctx, arcid) else NO_LOCAL_PROGRESS
        MutableStateFlow(initial)
    }
}
