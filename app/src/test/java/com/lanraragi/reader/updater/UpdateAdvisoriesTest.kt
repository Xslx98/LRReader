package com.lanraragi.reader.updater

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** Audit 2026-10-04 C46: the advisory.json notice channel for known-bad versions. */
class UpdateAdvisoriesTest {

    private fun advisory(id: String, min: Int, max: Int, vararg message: Pair<String, String>, url: String? = null) =
        Advisory(id = id, minVersionCode = min, maxVersionCode = max, message = mapOf(*message), url = url)

    @Test
    fun fetch_parsesTheFeedAndToleratesUnknownFields() {
        val server = MockWebServer().apply {
            enqueue(
                MockResponse().setBody(
                    """{"schema":2,"advisories":[{"id":"a1","min_version_code":12800,"max_version_code":12801,
                       "message":{"en":"Update now","zh":"请更新"},"url":"https://example.org/x","extra":true}]}"""
                )
            )
            start()
        }
        val list = runBlocking { UpdateAdvisories.fetch(OkHttpClient(), server.url("/advisory.json").toString()) }
        server.shutdown()

        assertEquals(
            listOf(advisory("a1", 12800, 12801, "en" to "Update now", "zh" to "请更新", url = "https://example.org/x")),
            list
        )
    }

    @Test
    fun fetch_failureOrGarbage_isAnEmptyList() {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(404))
            enqueue(MockResponse().setBody("<html>"))
            start()
        }
        val url = server.url("/advisory.json").toString()
        assertEquals(emptyList<Advisory>(), runBlocking { UpdateAdvisories.fetch(OkHttpClient(), url) })
        assertEquals(emptyList<Advisory>(), runBlocking { UpdateAdvisories.fetch(OkHttpClient(), url) })
        server.shutdown()
    }

    @Test
    fun pick_onlyUnseenAdvisoriesForThisVersion() {
        val list = listOf(
            advisory("old", 12000, 12099, "en" to "old"),
            advisory("seen", 12800, 12800, "en" to "seen"),
            advisory("hit", 12750, 12810, "en" to "hit"),
        )
        assertEquals("hit", UpdateAdvisories.pick(list, 12800, setOf("seen"), Locale.ENGLISH)?.id)
        assertNull(UpdateAdvisories.pick(list, 12800, setOf("seen", "hit"), Locale.ENGLISH))
        assertNull(UpdateAdvisories.pick(list, 12900, emptySet(), Locale.ENGLISH))
    }

    @Test
    fun messageFor_fallsBackFromTagToLanguageToEnglish() {
        val a = advisory("a", 0, 1, "zh-TW" to "繁", "zh" to "简", "en" to "en")
        assertEquals("繁", a.messageFor(Locale.TRADITIONAL_CHINESE))
        assertEquals("简", a.messageFor(Locale.SIMPLIFIED_CHINESE))
        assertEquals("en", a.messageFor(Locale.GERMAN))
    }

    @Test
    fun onlyHttpsLinksAreOffered() {
        assertEquals("https://x.org", advisory("a", 0, 1, url = "https://x.org").safeUrl)
        assertNull(advisory("a", 0, 1, url = "intent://evil").safeUrl)
        assertNull(advisory("a", 0, 1, url = "http://x.org").safeUrl)
    }
}
