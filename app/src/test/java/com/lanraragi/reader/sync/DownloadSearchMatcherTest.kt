package com.lanraragi.reader.sync

import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.mapper.toDownloadInfoView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Downloads page search matched tags against a list nothing ever filled,
 * so searching by tag never found anything. It now matches the view's
 * namespaced tags, built here through the real producer.
 */
class DownloadSearchMatcherTest {

    private val info = Archive(
        arcid = "a".repeat(40),
        title = "Mock Archive 3",
        tags = mapOf(
            "artist" to listOf("mock3"),
            "language" to listOf("english"),
            "misc" to listOf("full color"),
        ),
        pagecount = 3,
        progress = 0,
        extension = "zip",
        filename = "m.zip",
        thumbnailUrl = "",
        rating = 0f,
        isnew = false,
        lastreadtime = 0L,
        serverProfileId = 1L,
    ).toDownloadInfoView()

    @Test
    fun matchesATagByNamespaceAndValueOrByValueAlone() {
        assertTrue(DownloadSearchMatcher.matches(info, "artist:mock3"))
        assertTrue(DownloadSearchMatcher.matches(info, "Mock3"))
        assertTrue(DownloadSearchMatcher.matches(info, "full color"))
    }

    @Test
    fun everyCommaSeparatedTermMustMatch() {
        assertTrue(DownloadSearchMatcher.matches(info, "artist:mock3, language:english"))
        assertFalse(DownloadSearchMatcher.matches(info, "artist:mock3, language:japanese"))
    }

    @Test
    fun matchesTheTitleIgnoringCase() {
        assertTrue(DownloadSearchMatcher.matches(info, "archive 3"))
    }

    @Test
    fun aValueUnderAnotherNamespaceDoesNotMatchAQualifiedTerm() {
        assertFalse(DownloadSearchMatcher.matches(info, "artist:english"))
        assertFalse(DownloadSearchMatcher.matches(info, "nothing"))
    }
}
