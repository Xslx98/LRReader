package com.lanraragi.reader.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit 2026-10-04 C06 (R2): nothing that identifies the server or a key survives. */
class RedactorTest {

    @Test
    fun urlAuthority_isReplaced_schemeAndPathKept() {
        val out = Redactor.redact("GET https://lrr.example.com:3000/api/archives/abc/files failed")
        assertEquals("GET https://<host>/api/archives/abc/files failed", out)
    }

    @Test
    fun userInfoInUrl_isRemovedWithTheHost() {
        val out = Redactor.redact("http://admin:hunter2@10.0.0.5:3000/api/info")
        assertFalse(out, out.contains("hunter2"))
        assertFalse(out, out.contains("10.0.0.5"))
        assertEquals("http://<host>/api/info", out)
    }

    @Test
    fun bareIpv4_isReplaced() {
        assertEquals("connect to <ip> timed out", Redactor.redact("connect to 192.168.1.20:3000 timed out"))
    }

    @Test
    fun authorizationHeaders_areReplaced() {
        assertEquals("Authorization: <redacted>", Redactor.redact("Authorization: Bearer YWJjZGVm"))
        assertEquals("bearer <redacted> sent", Redactor.redact("bearer abcdef123 sent"))
    }

    @Test
    fun keyValueSecrets_areReplaced() {
        val out = Redactor.redact("api_key=s3cr3t&page=2 token: t0k password=pw")
        assertEquals("api_key=<redacted>&page=2 token: <redacted> password=<redacted>", out)
    }

    @Test
    fun ordinaryText_isUntouched() {
        val text = "Download failed: NO_SPACE after 3 attempts (basic check)"
        assertEquals(text, Redactor.redact(text))
    }

    @Test
    fun describeServerUrl_keepsOnlySchemeAndLan() {
        assertEquals("scheme=http, lan=true", Redactor.describeServerUrl("http://192.168.1.20:3000"))
        assertEquals("scheme=https, lan=false", Redactor.describeServerUrl("https://lrr.example.com/"))
        assertEquals("scheme=http, lan=true", Redactor.describeServerUrl("http://nas.local:3000"))
        assertEquals("none", Redactor.describeServerUrl(null))
    }

    @Test
    fun isLanHost_coversPrivateRanges() {
        assertTrue(Redactor.isLanHost("10.1.2.3"))
        assertTrue(Redactor.isLanHost("172.16.0.1"))
        assertTrue(Redactor.isLanHost("100.64.0.1"))
        assertTrue(Redactor.isLanHost("[fd00::1]"))
        assertFalse(Redactor.isLanHost("172.32.0.1"))
        assertFalse(Redactor.isLanHost("8.8.8.8"))
    }
}
