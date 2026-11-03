package com.lanraragi.reader.ui.scene.gallery.detail

import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.ArchiveDetail
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The detail request starts in onCreateView and the view-scoped collectors
 * subscribe in onViewCreated. A response that lands in between used to be
 * dropped (replay 0), leaving the page on its spinner with the read button
 * hidden for good (seen on the AVD baseline-profile run, 2026-11-03).
 */
class DetailRequestEventsTest {

    private fun detail(arcid: String) = ArchiveDetail(
        archive = Archive(
            arcid = arcid,
            title = arcid,
            tags = emptyMap(),
            pagecount = 0,
            progress = 0,
            extension = "",
            filename = "",
            thumbnailUrl = "",
            rating = 0f,
            isnew = false,
            lastreadtime = 0L,
            summary = null,
            serverProfileId = 0L,
        ),
        tagGroups = emptyList(),
        language = null,
        size = null,
    )

    /** What a collector that subscribes now receives, or null. */
    private fun <T> receivedBy(flow: SharedFlow<T>): T? =
        runBlocking { withTimeoutOrNull(200) { flow.first() } }

    @Test
    fun `a load that lands before anyone subscribes still reaches the collector`() {
        val events = DetailRequestEvents()
        val ad = detail("a")
        events.clear()
        events.loaded(ad)

        assertSame(ad, receivedBy(events.loaded))
    }

    @Test
    fun `a failure that lands before anyone subscribes still reaches the collector`() {
        val events = DetailRequestEvents()
        val error = RuntimeException("offline")
        events.clear()
        events.failed(error)
        val bannerError = RuntimeException("banner")
        events.failedWithContent(bannerError)

        assertSame(error, receivedBy(events.failed))
        assertSame(bannerError, receivedBy(events.failedWithContent))
    }

    @Test
    fun `a new request drops the previous request's results`() {
        val events = DetailRequestEvents()
        events.clear()
        events.loaded(detail("old"))
        events.failed(RuntimeException("old"))
        events.failedWithContent(RuntimeException("old"))

        events.clear()

        assertNull(receivedBy(events.loaded))
        assertNull(receivedBy(events.failed))
        assertNull(receivedBy(events.failedWithContent))
    }

    @Test
    fun `a late collector gets the latest result of the request`() {
        val events = DetailRequestEvents()
        events.clear()
        events.loaded(detail("first"))
        events.loaded(detail("second"))

        assertEquals("second", receivedBy(events.loaded)?.archive?.arcid)
    }
}
