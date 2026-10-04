package com.lanraragi.reader.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit 2026-10-04 C46: what the self-update accepts from the release API. */
class ApkIntegrityTest {

    @Test
    fun onlyThisRepositorysReleaseDownloadsAreTrusted() {
        assertTrue(ApkIntegrity.isTrustedUrl("https://github.com/Xslx98/LRReader/releases/download/v1.28.0/LRReader-v1.28.0-arm64.apk"))
        assertFalse(ApkIntegrity.isTrustedUrl("http://github.com/Xslx98/LRReader/releases/download/v1/a.apk"))
        assertFalse(ApkIntegrity.isTrustedUrl("https://github.com/someone/LRReader/releases/download/v1/a.apk"))
        assertFalse(ApkIntegrity.isTrustedUrl("https://evil.example/Xslx98/LRReader/releases/download/v1/a.apk"))
        assertFalse(ApkIntegrity.isTrustedUrl("https://github.com.evil.example/Xslx98/LRReader/releases/download/v1/a.apk"))
        assertFalse(ApkIntegrity.isTrustedUrl("not a url"))
    }

    @Test
    fun fileNamesAreReducedToAPlainApkName() {
        assertEquals("LRReader-v1.28.0-arm64.apk", ApkIntegrity.safeFileName("LRReader-v1.28.0-arm64.apk"))
        assertEquals(ApkIntegrity.FALLBACK_NAME, ApkIntegrity.safeFileName("../../shared_prefs/x.apk"))
        assertEquals(ApkIntegrity.FALLBACK_NAME, ApkIntegrity.safeFileName("payload.so"))
        assertEquals(ApkIntegrity.FALLBACK_NAME, ApkIntegrity.safeFileName(null))
    }

    @Test
    fun sizeAndDigestMustMatchWhenKnown() {
        val hex = "ab".repeat(32)
        assertNull(ApkIntegrity.mismatch(10, hex, 10, "sha256:$hex"))
        assertNull(ApkIntegrity.mismatch(10, hex, 10, "SHA256:${hex.uppercase()}"))
        assertNotNull(ApkIntegrity.mismatch(9, hex, 10, "sha256:$hex"))
        assertNotNull(ApkIntegrity.mismatch(10, hex, 10, "sha256:" + "cd".repeat(32)))
        // Unknown size / no digest / another algorithm: nothing to compare against.
        assertNull(ApkIntegrity.mismatch(10, hex, 0, null))
        assertNull(ApkIntegrity.mismatch(10, hex, 10, "md5:00"))
    }
}
