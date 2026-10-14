package com.lanraragi.reader.ui.scene.gallery.detail

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * The in-flight thumbnail request of each page of one preview grid, so the
 * grid can drop requests it no longer needs: pages a fling scrolled past and
 * everything of an entry the grid has left. Cancelling a request releases its
 * share of the [com.lanraragi.reader.client.api.PageThumbnailRepository]
 * fetch, which is cancelled once no grid waits for it (audit 2026-10-13 PERF-01).
 */
internal class PageFetchJobs {

    private val jobs = ConcurrentHashMap<Int, Job>()

    /** Records [job] as the request of [page]; it is forgotten when it completes. */
    fun track(page: Int, job: Job) {
        jobs[page] = job
        job.invokeOnCompletion { jobs.remove(page, job) }
    }

    /** Cancels the requests of pages outside [window] and returns those pages. */
    fun cancelOutside(window: IntRange): Set<Int> {
        val cancelled = HashSet<Int>()
        for ((page, job) in jobs) {
            if (page !in window) {
                job.cancel()
                cancelled.add(page)
            }
        }
        return cancelled
    }

    fun cancelAll() {
        for (job in jobs.values) job.cancel()
        jobs.clear()
    }
}
