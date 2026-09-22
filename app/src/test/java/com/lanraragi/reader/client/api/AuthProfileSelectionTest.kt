package com.lanraragi.reader.client.api

import com.lanraragi.reader.dao.ServerProfile
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which profile's key a request gets when profiles share host and port (audit 2026-09-22 A22). */
class AuthProfileSelectionTest {

    private fun candidate(id: Long, url: String, active: Boolean = false) =
        ProfileUrlCandidate(ServerProfile(id = id, name = "p$id", url = url, isActive = active), url.toHttpUrl())

    private val a = candidate(1, "https://nas.example/lrr-a")
    private val b = candidate(2, "https://nas.example/lrr-b")

    @Test
    fun eachSubPathGetsItsOwnProfile() {
        val all = listOf(a, b)
        assertEquals(1L, pickSchemeMatch(all, "https://nas.example/lrr-a/api/info".toHttpUrl())!!.profile.id)
        assertEquals(2L, pickSchemeMatch(all, "https://nas.example/lrr-b/api/info".toHttpUrl())!!.profile.id)
    }

    @Test
    fun prefixMatchesOnSegmentBoundariesOnly() {
        val root = candidate(3, "https://nas.example/")
        // "/lrr-ab" must not be claimed by "/lrr-a".
        assertEquals(3L, pickSchemeMatch(listOf(a, root), "https://nas.example/lrr-ab/api".toHttpUrl())!!.profile.id)
    }

    @Test
    fun sameBaseUrlPrefersTheActiveProfile() {
        val twin = candidate(4, "https://nas.example/lrr-a", active = true)
        assertEquals(4L, pickSchemeMatch(listOf(a, twin), "https://nas.example/lrr-a/api".toHttpUrl())!!.profile.id)
    }

    @Test
    fun schemeMismatchStillRefuses() {
        assertNull(pickSchemeMatch(listOf(a), "http://nas.example/lrr-a/api".toHttpUrl()))
    }
}
