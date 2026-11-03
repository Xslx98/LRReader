package com.lanraragi.reader.client.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LRRUrlHelper]: URL normalization and scheme detection.
 * LAN-address classification lives in [LRRUrlHelperLanAddressTest].
 */
class LRRUrlHelperTest {

    // ── normalizeUrl ───────────────────────────────────────────────

    @Test
    fun normalizeUrl_stripTrailingSlash() {
        assertEquals("http://host", LRRUrlHelper.normalizeUrl("http://host/"))
    }

    @Test
    fun normalizeUrl_stripMultipleSlashes() {
        assertEquals("http://host", LRRUrlHelper.normalizeUrl("http://host///"))
    }

    @Test
    fun normalizeUrl_trimWhitespace() {
        assertEquals("http://host", LRRUrlHelper.normalizeUrl("  http://host  "))
    }

    @Test
    fun normalizeUrl_noChange() {
        assertEquals("http://host", LRRUrlHelper.normalizeUrl("http://host"))
    }

    @Test
    fun normalizeUrl_emptyString() {
        assertEquals("", LRRUrlHelper.normalizeUrl(""))
    }

    // ── hasExplicitScheme ──────────────────────────────────────────

    @Test
    fun hasExplicitScheme_https() {
        assertTrue(LRRUrlHelper.hasExplicitScheme("https://host.com"))
    }

    @Test
    fun hasExplicitScheme_http() {
        assertTrue(LRRUrlHelper.hasExplicitScheme("http://host.com"))
    }

    @Test
    fun hasExplicitScheme_bare() {
        assertFalse(LRRUrlHelper.hasExplicitScheme("host.com"))
    }

    @Test
    fun hasExplicitScheme_caseInsensitive() {
        assertTrue(LRRUrlHelper.hasExplicitScheme("HTTPS://HOST.COM"))
        assertTrue(LRRUrlHelper.hasExplicitScheme("Http://Host.Com"))
    }

    @Test
    fun hasExplicitScheme_ipWithPort() {
        assertFalse(LRRUrlHelper.hasExplicitScheme("192.168.1.1:3000"))
    }
}
