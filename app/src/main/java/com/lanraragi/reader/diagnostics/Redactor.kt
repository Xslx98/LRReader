package com.lanraragi.reader.diagnostics

/**
 * Scrubs text that may leave the device inside a diagnostics bundle (audit
 * 2026-10-04 C06, ruling R2): server addresses, IPs, API keys and
 * credentials. Applied to every ring-buffer line, crash breadcrumb and
 * exported setting value, so a stray `Log` message that embeds a URL or an
 * `Authorization` header cannot leak the user's server or key.
 *
 * What survives: the URL scheme (http vs https matters for cleartext bugs)
 * and the path, because paths identify the API endpoint that failed.
 */
object Redactor {

    private const val HOST = "<host>"
    private const val IP = "<ip>"
    private const val SECRET = "<redacted>"
    private val LAN_SUFFIXES = listOf(".local", ".lan", ".home.arpa")

    /** `scheme://userinfo@host:port` — the authority part of any URL. */
    private val URL_AUTHORITY = Regex("""\b([a-zA-Z][a-zA-Z0-9+.-]*)://[^\s/?#"'<>]+""")

    /** Bare IPv4 addresses (LAN servers are usually reached by IP). */
    private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b""")

    /** `Authorization: Bearer xyz`, `Authorization: xyz`, and a bare `Bearer xyz`. */
    private val AUTH_HEADER = Regex(
        """(?i)\b(authorization\s*[:=]\s*|bearer\s+)(?:bearer\s+|basic\s+)?[A-Za-z0-9+/=._~-]+"""
    )

    /** `api_key=…`, `apikey: …`, `token=…`, `password=…`, `key=…` in query strings or messages. */
    private val KEY_VALUE = Regex(
        """(?i)\b(api[_-]?key|apikey|key|token|access[_-]?token|password|passwd|pwd|secret|cookie)(\s*[=:]\s*)[^\s&,;"']+"""
    )

    fun redact(text: String): String {
        var out = AUTH_HEADER.replace(text) { m -> (m.groupValues[1]) + SECRET }
        out = KEY_VALUE.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + SECRET }
        out = URL_AUTHORITY.replace(out) { m -> m.groupValues[1] + "://" + HOST }
        out = IPV4.replace(out, IP)
        return out
    }

    /**
     * Describes a server base URL without revealing it: only the scheme and
     * whether the host is a private/LAN address survive.
     */
    fun describeServerUrl(url: String?): String {
        if (url.isNullOrBlank()) return "none"
        val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?')
            .substringAfterLast('@').let { stripPort(it) }.lowercase()
        val lan = isLanHost(host)
        return "scheme=${scheme.ifEmpty { "?" }}, lan=$lan"
    }

    private fun stripPort(hostPort: String): String {
        if (hostPort.startsWith("[")) return hostPort.substringBefore(']') + "]"
        return hostPort.substringBefore(':')
    }

    internal fun isLanHost(host: String): Boolean {
        if (host == "localhost" || LAN_SUFFIXES.any { host.endsWith(it) }) return true
        if (host.startsWith("[")) {
            val h = host.trim('[', ']')
            return h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
        }
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        val (a, b) = parts
        return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) ||
            (a == 169 && b == 254) || (a == 100 && b in 64..127)
    }
}
