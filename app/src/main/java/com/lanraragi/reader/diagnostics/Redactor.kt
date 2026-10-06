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

    // ---- Hosts without a scheme (audit 2026-10-06 C06 / SEC-02) ----
    // Exception messages name the server without a URL: OkHttp, libcore and
    // the JDK each have their own wording. These rules key on that wording or
    // on an address shape, never on "dotted words" in general, so class
    // names, package names and `(File.kt:12)` stack frames survive.

    /** One IPv6 group. */
    private const val H = "[0-9A-Fa-f]{1,4}"

    /** IPv6 literal: the full eight groups, or a `::`-compressed form with at least one group. */
    private const val IPV6_CORE = "(?:(?:$H:){7}$H|$H(?::$H){0,6}::(?:$H(?::$H){0,6})?|::$H(?::$H){0,6})"

    private val IPV6 = Regex("""(?<![\w:])$IPV6_CORE(?![\w:])""")

    /** libcore `Unable to resolve host "x"` (quoted or not). */
    private val RESOLVE_HOST = Regex("""(?i)(\bresolve\s+host\s+)("?)[^"\s]+("?)""")

    /** JDK `UnknownHostException: x` / `x: Name or service not known`. */
    private val UNKNOWN_HOST = Regex("""(UnknownHostException:\s+)(?!Unable\b)[\w.-]+""")

    /** OkHttp `Hostname x not verified`. */
    private val HOSTNAME_NOT_VERIFIED = Regex("""(?i)(\bhostname\s+)\S+(?=\s+not\s+verified)""")

    /** Network security policy `CLEARTEXT communication to x not permitted`. */
    private val CLEARTEXT_TO = Regex("""(?i)(\bcommunication\s+to\s+)\S+(?=\s+not\s+permitted)""")

    /** `LRRApiUtils` `Invalid server URL: x` when x has no scheme (a scheme URL is handled above). */
    private val SERVER_URL = Regex("""(?i)(\bserver\s+url:\s*)(?![a-z][a-z0-9+.-]*://)\S+""")

    /** OkHttp's certificate details under a `Hostname ... not verified` message. */
    private val CERT_PIN = Regex("""(?i)(\bcertificate:\s*)sha(?:1|256)/\S+""")
    private val CERT_DN = Regex("""(?m)^(\s*DN:\s*)\S.*$""")
    private val CERT_SAN = Regex("""(?i)(\bsubjectAltNames:\s*)\[[^\]]*]""")

    /**
     * `InetSocketAddress.toString()` — `host/1.2.3.4:3000`, `host/2001:db8::1`,
     * `host/[::1]:3000`, `host/<unresolved>:3000` — as in libcore's
     * "failed to connect to host/ip (port n)" and OkHttp's "Failed to connect to".
     */
    private val HOST_SLASH_ADDRESS = Regex(
        """(?<![\w./-])[\w-]+(?:\.[\w-]+)*/(?=(?:\d{1,3}\.){3}\d{1,3}|<unresolved>|\[|[0-9A-Fa-f]{0,4}:[0-9A-Fa-f]{0,4}:)"""
    )

    /**
     * `[user@]host.tld:port` without a scheme. The last label must be letters
     * and not a source extension, and a `(` before it marks a stack frame.
     */
    private val HOST_PORT = Regex(
        """(?<![\w.(\[$-])(?:[\w.%+-]+@)?(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\.)+([A-Za-z]{2,63}):\d{1,5}(?!\d)"""
    )
    private val SOURCE_EXTENSIONS = setOf("kt", "kts", "java", "xml", "json", "txt", "log")

    // ---- Archive titles in download paths (audit 2026-10-06 C06) ----
    // A download directory is named after the archive title (DownloadDirNaming),
    // so "<root>/<title>/0001.jpg" in a FileNotFoundException names the archive.
    // The segment right below a download root is replaced; the rest of the path
    // (page file name, error reason) is kept.

    private const val TITLE = "<title>"
    private const val PATH = "<path>"

    /**
     * One directory name below a root. FileUtils.sanitizeFilename strips
     * `\ / : * ? " < > |`, so a title never holds them: the segment ends at a
     * separator, at the ": open failed" that follows a libcore path, or at the
     * end of the line. Excluding `<`/`>` also keeps the rule idempotent.
     */
    private const val SEGMENT = """[^/\\:*?"<>|\r\n]+"""

    /** The default roots: `files/download` in the external or internal files dir. */
    private val DEFAULT_ROOT_CHILD = Regex("""(/files/download/)$SEGMENT""")

    /** Percent-encoded SAF document id: `/document/primary%3AManga%2FTitle%2F0001.jpg`. */
    private val SAF_DOCUMENT = Regex("""(/document/[A-Za-z0-9._-]+%3A)[^\s"'<>]+""")

    private val rootsLock = Any()
    private val rootPrefixes = LinkedHashSet<String>()

    @Volatile
    private var rootRules: List<Regex> = emptyList()

    /**
     * Adds a download root (a file path, `file://` URI or SAF `content://`
     * tree URI) whose child directories are archive titles. Called wherever a
     * root is resolved; repeats are cheap no-ops.
     */
    fun registerDownloadRoot(root: String?) {
        if (root.isNullOrBlank()) return
        val prefixes = prefixesOf(root)
        synchronized(rootsLock) {
            if (!rootPrefixes.addAll(prefixes)) return
            rootRules = rootPrefixes.map { Regex("(" + Regex.escape(it) + "/)" + SEGMENT) }
        }
    }

    /** Test hook: forget every registered root. */
    internal fun clearDownloadRoots() = synchronized(rootsLock) {
        rootPrefixes.clear()
        rootRules = emptyList()
    }

    /** The literal path prefixes [root] appears as in messages: decoded and, if different, encoded. */
    private fun prefixesOf(root: String): List<String> {
        val raw = when {
            root.startsWith("file://") -> root.removePrefix("file://")
            root.startsWith("content://") -> root.substringAfter("/tree/", "").substringBefore('/')
            else -> root
        }
        val decoded = percentDecode(raw)
        return listOf(decoded, raw).map { it.trimEnd('/') }.filter { it.length >= MIN_ROOT_LENGTH }.distinct()
    }

    private const val MIN_ROOT_LENGTH = 2

    /** `%XX` escapes to UTF-8 text; a malformed escape is kept as is (never throws). */
    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val bytes = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val escape = if (s[i] == '%' && i + 2 < s.length) s.substring(i + 1, i + ESCAPE_LENGTH) else ""
            if (escape.isNotEmpty() && escape.all { Character.digit(it, HEX_RADIX) >= 0 }) {
                bytes.write(escape.toInt(HEX_RADIX))
                i += ESCAPE_LENGTH
            } else {
                val cp = s.codePointAt(i)
                bytes.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
                i += Character.charCount(cp)
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private const val HEX_RADIX = 16
    private const val ESCAPE_LENGTH = 3

    private fun redactTitles(text: String): String {
        var out = DEFAULT_ROOT_CHILD.replace(text) { m -> m.groupValues[1] + TITLE }
        out = SAF_DOCUMENT.replace(out) { m -> m.groupValues[1] + PATH }
        for (rule in rootRules) out = rule.replace(out) { m -> m.groupValues[1] + TITLE }
        return out
    }

    fun redact(text: String): String {
        // Titles first: the host rules below must not split a title segment.
        var out = AUTH_HEADER.replace(redactTitles(text)) { m -> (m.groupValues[1]) + SECRET }
        out = KEY_VALUE.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + SECRET }
        out = URL_AUTHORITY.replace(out) { m -> m.groupValues[1] + "://" + HOST }
        out = RESOLVE_HOST.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + HOST + m.groupValues[3] }
        out = UNKNOWN_HOST.replace(out) { m -> m.groupValues[1] + HOST }
        out = HOSTNAME_NOT_VERIFIED.replace(out) { m -> m.groupValues[1] + HOST }
        out = CLEARTEXT_TO.replace(out) { m -> m.groupValues[1] + HOST }
        out = SERVER_URL.replace(out) { m -> m.groupValues[1] + HOST }
        out = CERT_PIN.replace(out) { m -> m.groupValues[1] + SECRET }
        out = CERT_DN.replace(out) { m -> m.groupValues[1] + SECRET }
        out = CERT_SAN.replace(out) { m -> m.groupValues[1] + "[" + HOST + "]" }
        out = HOST_SLASH_ADDRESS.replace(out) { "$HOST/" }
        out = HOST_PORT.replace(out) { m ->
            if (m.groupValues[1].lowercase() in SOURCE_EXTENSIONS) m.value else HOST
        }
        // IPv4 before IPv6: `::ffff:1.2.3.4` must not leave the dotted tail behind.
        out = IPV4.replace(out, IP)
        out = IPV6.replace(out, IP)
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
