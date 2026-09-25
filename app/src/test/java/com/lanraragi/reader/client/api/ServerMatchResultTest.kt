package com.lanraragi.reader.client.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [matchesConfiguredServer] classifies by pure string equality on the
 * HttpUrl-normalised host, effective port and scheme. These are the cases a
 * socket round trip cannot express (default ports, IP literals), asserted on
 * the matcher itself.
 */
class ServerMatchResultTest {

    @Test
    fun classifiesRequestsAgainstTheConfiguredServer() {
        val cases = listOf(
            // Omitted default port equals the explicit default.
            Triple("http://example.local/api/info", "http://example.local", ServerMatchResult.MATCH),
            Triple("http://example.local:80/api/info", "http://example.local", ServerMatchResult.MATCH),
            Triple("https://example.local:443/api/info", "https://example.local", ServerMatchResult.MATCH),
            // IPv4 / IPv6 literals (brackets stripped by HttpUrl).
            Triple("http://192.168.1.10:3000/api/info", "http://192.168.1.10:3000", ServerMatchResult.MATCH),
            Triple("http://[::1]:3000/api/info", "http://[::1]:3000", ServerMatchResult.MATCH),
            Triple("http://[::1]:3001/api/info", "http://[::1]:3000", ServerMatchResult.MISMATCH),
            // Host is case-insensitive; another host or port never gets the key.
            Triple("http://example.local:3000/api/info", "http://EXAMPLE.local:3000", ServerMatchResult.MATCH),
            Triple("http://other.local:3000/api/info", "http://example.local:3000", ServerMatchResult.MISMATCH),
            Triple("http://example.local:3001/api/info", "http://example.local:3000", ServerMatchResult.MISMATCH),
            // Same host:port on another scheme is a downgrade, not a pass-through.
            Triple("http://example.local:3000/api/info", "https://example.local:3000", ServerMatchResult.SCHEME_DOWNGRADE),
        )
        for ((request, server, expected) in cases) {
            assertEquals("$request vs $server", expected, matchesConfiguredServer(request.toHttpUrl(), server))
        }
    }
}
