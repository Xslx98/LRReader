package com.lanraragi.reader.ui.scene.gallery.detail

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageFetchJobsTest {

    @Test
    fun cancelOutside_cancelsOnlyPagesOutsideTheWindow() {
        val jobs = PageFetchJobs()
        val byPage = (0..9).associateWith { Job() }
        byPage.forEach { (page, job) -> jobs.track(page, job) }

        val cancelled = jobs.cancelOutside(3..6)

        assertEquals(setOf(0, 1, 2, 7, 8, 9), cancelled)
        byPage.forEach { (page, job) -> assertEquals("page $page", page in 3..6, job.isActive) }
        // Cancelled jobs are forgotten, so a second pass reports nothing new.
        assertEquals(emptySet<Int>(), jobs.cancelOutside(3..6))
    }

    @Test
    fun completedJobIsForgotten_andAReplacedJobKeepsTheNewOne() {
        val jobs = PageFetchJobs()
        val old = Job()
        jobs.track(1, old)
        val replacement = Job()
        jobs.track(1, replacement)
        old.complete()

        jobs.cancelAll()

        assertTrue(replacement.isCancelled)
        assertFalse(old.isCancelled)
    }
}
