/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lanraragi.reader.client.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [LRRUrlHelper.isLanAddress] — the private/LAN classifier that
 * gates cleartext transmission of the API key. Kept in Kotlin (new-code
 * convention) and single-sourced here; the classifier feeds a security gate,
 * so both false positives (a WAN host waved through as LAN) and false
 * negatives (a genuine LAN host refused) are bugs.
 */
class LRRUrlHelperLanAddressTest {

    // ── IPv4 private ranges ───────────────────────────────────────────

    @Test fun ipv4_192_168() = assertTrue(LRRUrlHelper.isLanAddress("http://192.168.1.1:3000"))
    @Test fun ipv4_10() = assertTrue(LRRUrlHelper.isLanAddress("http://10.0.0.1"))
    @Test fun ipv4_172_16() = assertTrue(LRRUrlHelper.isLanAddress("http://172.16.0.1"))
    @Test fun ipv4_172_31() = assertTrue(LRRUrlHelper.isLanAddress("http://172.31.255.255"))
    @Test fun ipv4_172_32_out() = assertFalse(LRRUrlHelper.isLanAddress("http://172.32.0.1"))
    @Test fun ipv4_172_15_out() = assertFalse(LRRUrlHelper.isLanAddress("http://172.15.0.1"))
    @Test fun ipv4_127_loopback() = assertTrue(LRRUrlHelper.isLanAddress("http://127.0.0.1"))
    @Test fun ipv4_linkLocal() = assertTrue(LRRUrlHelper.isLanAddress("http://169.254.1.1"))
    @Test fun ipv4_cgnat() {
        assertTrue(LRRUrlHelper.isLanAddress("http://100.64.0.1:3000"))
        assertTrue(LRRUrlHelper.isLanAddress("http://100.127.255.255"))
    }
    @Test fun ipv4_cgnat_out() {
        assertFalse(LRRUrlHelper.isLanAddress("http://100.63.0.1"))
        assertFalse(LRRUrlHelper.isLanAddress("http://100.128.0.1"))
    }

    // ── localhost / mDNS ──────────────────────────────────────────────

    @Test fun localhost() = assertTrue(LRRUrlHelper.isLanAddress("http://localhost:3000"))
    @Test fun dotLocal() = assertTrue(LRRUrlHelper.isLanAddress("http://nas.local"))

    // ── hostnames that merely start with a private prefix are NOT LAN ──

    @Test fun prefix_192_168_hostname_notLan() =
        assertFalse(LRRUrlHelper.isLanAddress("http://192.168.evil.com:3000"))
    @Test fun prefix_10_hostname_notLan() =
        assertFalse(LRRUrlHelper.isLanAddress("http://10.attacker.example"))
    @Test fun prefix_172_hostname_notLan() =
        assertFalse(LRRUrlHelper.isLanAddress("http://172.20.foo.com"))
    @Test fun bareHostname_notLan() =
        assertFalse(LRRUrlHelper.isLanAddress("http://nas:3000"))
    @Test fun publicDomain_notLan() =
        assertFalse(LRRUrlHelper.isLanAddress("https://lr.example.com"))

    // ── malformed IPv4 literals must not be classified LAN ────────────

    @Test fun octetOutOfRange_notLan() =
        assertFalse(LRRUrlHelper.isLanAddress("http://192.168.1.999"))

    @Test fun leadingZeroOctet_notLan() {
        // A BSD-lineage resolver reads a leading-zero octet as OCTAL, so
        // "010.0.0.1" may connect to public 8.0.0.1 while a decimal parse
        // classifies it as private 10/8 — a gate/resolver disagreement that
        // would leak the key. Reject leading-zero octets outright.
        assertFalse(LRRUrlHelper.isLanAddress("http://010.0.0.1"))
        assertFalse(LRRUrlHelper.isLanAddress("http://192.168.001.1"))
    }

    // ── IPv6 ──────────────────────────────────────────────────────────

    @Test fun ipv6_loopback_compressed() =
        assertTrue(LRRUrlHelper.isLanAddress("http://[::1]:3000"))
    @Test fun ipv6_loopback_uncompressed() =
        assertTrue(LRRUrlHelper.isLanAddress("http://[0:0:0:0:0:0:0:1]:3000"))
    @Test fun ipv6_uniqueLocal_fd() =
        assertTrue(LRRUrlHelper.isLanAddress("http://[fd00::2]:3000"))
    @Test fun ipv6_uniqueLocal_fc00() =
        assertTrue(LRRUrlHelper.isLanAddress("http://[fc00::1]:3000"))
    @Test fun ipv6_linkLocal() =
        assertTrue(LRRUrlHelper.isLanAddress("http://[fe80::1]:3000"))
    @Test fun ipv6_v4mapped_private() =
        assertTrue(LRRUrlHelper.isLanAddress("http://[::ffff:192.168.1.5]:3000"))
    @Test fun ipv6_public_notLan() =
        assertFalse(LRRUrlHelper.isLanAddress("http://[2001:db8::1]:3000"))
    @Test fun ipv6_fc0_prefixOnly_notLan() =
        // fc0:: has first byte 0x0f — outside fc00::/7. A raw startsWith("fc")
        // string match would wrongly admit it.
        assertFalse(LRRUrlHelper.isLanAddress("http://[fc0::1]:3000"))
    @Test fun ipv6_v4mapped_public_notLan() =
        assertFalse(LRRUrlHelper.isLanAddress("http://[::ffff:8.8.8.8]:3000"))

    // ── robustness ────────────────────────────────────────────────────

    @Test fun invalidUrl_notLan() = assertFalse(LRRUrlHelper.isLanAddress("not-a-url"))
    @Test fun emptyString_notLan() = assertFalse(LRRUrlHelper.isLanAddress(""))

    // ── isInsecureWanUrl (plain HTTP to a non-LAN host) ───────────────

    @Test fun insecureWan_httpPublicIp() =
        assertTrue(LRRUrlHelper.isInsecureWanUrl("http://203.0.113.5:3000"))
    @Test fun insecureWan_httpsPublicIp_secure() =
        assertFalse(LRRUrlHelper.isInsecureWanUrl("https://203.0.113.5:3000"))
    @Test fun insecureWan_httpPublicDomain() =
        assertTrue(LRRUrlHelper.isInsecureWanUrl("http://example.com:3000"))
    @Test fun insecureWan_httpLanIp_secure() =
        assertFalse(LRRUrlHelper.isInsecureWanUrl("http://192.168.1.100:3000"))
    @Test fun insecureWan_http10Network_secure() =
        assertFalse(LRRUrlHelper.isInsecureWanUrl("http://10.0.0.1:3000"))
    @Test fun insecureWan_http172Network_secure() =
        assertFalse(LRRUrlHelper.isInsecureWanUrl("http://172.16.0.1:3000"))
    @Test fun insecureWan_httpLocalhost_secure() =
        assertFalse(LRRUrlHelper.isInsecureWanUrl("http://localhost:3000"))
    @Test fun insecureWan_httpDotLocal_secure() =
        assertFalse(LRRUrlHelper.isInsecureWanUrl("http://myserver.local:3000"))
}
