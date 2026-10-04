package com.lanraragi.reader.updater

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Checks around the self-update download (audit 2026-10-04 C46). The APK is
 * the one thing the app installs over itself, so nothing about it is taken
 * on trust from the API response:
 *  - it is fetched only from this repository's GitHub release downloads;
 *  - the file name comes from a fixed safe alphabet;
 *  - the downloaded bytes must match the asset's size and SHA-256 digest
 *    as GitHub reports them.
 */
internal object ApkIntegrity {
    private const val HOST = "github.com"
    private const val PATH_PREFIX = "/Xslx98/LRReader/releases/download/"
    private const val SHA256_PREFIX = "sha256:"
    const val FALLBACK_NAME = "LRReader-update.apk"
    private val SAFE_NAME = Regex("""^[A-Za-z0-9._-]{1,100}\.apk$""")

    /** True only for an https URL of a release download of this repository. */
    fun isTrustedUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull()
        return parsed != null && parsed.isHttps && parsed.host == HOST && parsed.encodedPath.startsWith(PATH_PREFIX)
    }

    /** [name] when it is a plain `.apk` file name, else [FALLBACK_NAME]. */
    fun safeFileName(name: String?): String =
        if (name != null && SAFE_NAME.matches(name)) name else FALLBACK_NAME

    /**
     * Why the downloaded file must not be installed, or null when it matches.
     * An unknown size (<= 0) or a digest in another algorithm is not checked.
     */
    fun mismatch(bytes: Long, sha256Hex: String, expectedSize: Long, expectedDigest: String?): String? {
        if (expectedSize > 0 && bytes != expectedSize) {
            return "size $bytes != expected $expectedSize"
        }
        val expected = expectedDigest?.takeIf { it.startsWith(SHA256_PREFIX, ignoreCase = true) }
            ?.substring(SHA256_PREFIX.length)
        if (expected != null && !expected.equals(sha256Hex, ignoreCase = true)) {
            return "sha256 $sha256Hex != expected $expected"
        }
        return null
    }
}
