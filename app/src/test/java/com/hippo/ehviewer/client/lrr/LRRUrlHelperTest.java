package com.hippo.ehviewer.client.lrr;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link LRRUrlHelper} — URL normalization, scheme detection,
 * and LAN address detection.
 */
public class LRRUrlHelperTest {

    // ── normalizeUrl ───────────────────────────────────────────────

    @Test
    public void normalizeUrl_stripTrailingSlash() {
        assertEquals("http://host", LRRUrlHelper.normalizeUrl("http://host/"));
    }

    @Test
    public void normalizeUrl_stripMultipleSlashes() {
        assertEquals("http://host", LRRUrlHelper.normalizeUrl("http://host///"));
    }

    @Test
    public void normalizeUrl_trimWhitespace() {
        assertEquals("http://host", LRRUrlHelper.normalizeUrl("  http://host  "));
    }

    @Test
    public void normalizeUrl_noChange() {
        assertEquals("http://host", LRRUrlHelper.normalizeUrl("http://host"));
    }

    @Test
    public void normalizeUrl_emptyString() {
        assertEquals("", LRRUrlHelper.normalizeUrl(""));
    }

    // ── hasExplicitScheme ──────────────────────────────────────────

    @Test
    public void hasExplicitScheme_https() {
        assertTrue(LRRUrlHelper.hasExplicitScheme("https://host.com"));
    }

    @Test
    public void hasExplicitScheme_http() {
        assertTrue(LRRUrlHelper.hasExplicitScheme("http://host.com"));
    }

    @Test
    public void hasExplicitScheme_bare() {
        assertFalse(LRRUrlHelper.hasExplicitScheme("host.com"));
    }

    @Test
    public void hasExplicitScheme_caseInsensitive() {
        assertTrue(LRRUrlHelper.hasExplicitScheme("HTTPS://HOST.COM"));
        assertTrue(LRRUrlHelper.hasExplicitScheme("Http://Host.Com"));
    }

    @Test
    public void hasExplicitScheme_ipWithPort() {
        assertFalse(LRRUrlHelper.hasExplicitScheme("192.168.1.1:3000"));
    }

    // ── isLanAddress ──────────────────────────────────────────────

    @Test
    public void isLanAddress_192_168() {
        assertTrue(LRRUrlHelper.isLanAddress("http://192.168.1.1:3000"));
    }

    @Test
    public void isLanAddress_10() {
        assertTrue(LRRUrlHelper.isLanAddress("http://10.0.0.1"));
    }

    @Test
    public void isLanAddress_172_16() {
        assertTrue(LRRUrlHelper.isLanAddress("http://172.16.0.1"));
    }

    @Test
    public void isLanAddress_172_31() {
        assertTrue(LRRUrlHelper.isLanAddress("http://172.31.255.255"));
    }

    @Test
    public void isLanAddress_172_32_outOfRange() {
        assertFalse(LRRUrlHelper.isLanAddress("http://172.32.0.1"));
    }

    @Test
    public void isLanAddress_172_15_outOfRange() {
        assertFalse(LRRUrlHelper.isLanAddress("http://172.15.0.1"));
    }

    @Test
    public void isLanAddress_localhost() {
        assertTrue(LRRUrlHelper.isLanAddress("http://localhost:3000"));
    }

    @Test
    public void isLanAddress_127_0_0_1() {
        assertTrue(LRRUrlHelper.isLanAddress("http://127.0.0.1"));
    }

    @Test
    public void isLanAddress_dotLocal() {
        assertTrue(LRRUrlHelper.isLanAddress("http://nas.local"));
    }

    @Test
    public void isLanAddress_publicDomain() {
        assertFalse(LRRUrlHelper.isLanAddress("https://lr.example.com"));
    }

    @Test
    public void isLanAddress_invalidUrl() {
        // Should not crash, just return false
        assertFalse(LRRUrlHelper.isLanAddress("not-a-url"));
    }

    @Test
    public void isLanAddress_emptyString() {
        assertFalse(LRRUrlHelper.isLanAddress(""));
    }
}
