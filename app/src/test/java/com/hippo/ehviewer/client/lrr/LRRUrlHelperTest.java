package com.hippo.ehviewer.client.lrr;

import com.lanraragi.reader.client.api.*;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link LRRUrlHelper} — URL normalization and scheme
 * detection. LAN-address classification lives in
 * {@code LRRUrlHelperLanAddressTest} (Kotlin), single-sourced there.
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
}
