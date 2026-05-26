package com.hippo.ehviewer.updater

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [GhRelease] — kotlinx-serialization deserialization + versionCode parsing.
 *
 * Mirrors the style of LRRArchiveTest / LRRServerInfoTest in app/src/test/java/com/hippo/ehviewer/client/lrr/data/.
 */
class GhReleaseTest {

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }

    @Test
    fun parseMinimalRelease() {
        val payload = """
            {
                "tag_name": "v1.13.0",
                "name": "v1.13.0",
                "html_url": "https://github.com/Xslx98/LRReader/releases/tag/v1.13.0",
                "prerelease": false,
                "body": "",
                "assets": []
            }
        """.trimIndent()
        val release = json.decodeFromString<GhRelease>(payload)
        assertEquals("v1.13.0", release.tagName)
        assertEquals("v1.13.0", release.name)
        assertFalse(release.prerelease)
        assertEquals(0, release.assets.size)
    }

    @Test
    fun parseFullReleaseWithAssets() {
        val payload = """
            {
                "tag_name": "v1.14.0",
                "name": "v1.14.0 — In-app Update",
                "html_url": "https://github.com/Xslx98/LRReader/releases/tag/v1.14.0",
                "prerelease": false,
                "body": "## What's New\n- in-app update\n- ...",
                "assets": [
                    {
                        "name": "LRReader-v1.14.0.apk",
                        "browser_download_url": "https://github.com/Xslx98/LRReader/releases/download/v1.14.0/LRReader-v1.14.0.apk",
                        "size": 7801058,
                        "content_type": "application/vnd.android.package-archive"
                    }
                ]
            }
        """.trimIndent()
        val release = json.decodeFromString<GhRelease>(payload)
        assertEquals("v1.14.0", release.tagName)
        assertFalse(release.prerelease)
        assertEquals(1, release.assets.size)
        val asset = release.assets[0]
        assertEquals("LRReader-v1.14.0.apk", asset.name)
        assertEquals(7801058L, asset.size)
        assertEquals("application/vnd.android.package-archive", asset.contentType)
    }

    @Test
    fun ignoresUnknownFields() {
        val payload = """
            {
                "tag_name": "v1.13.0",
                "id": 999999,
                "url": "https://api.github.com/...",
                "author": { "login": "Xslx98" },
                "published_at": "2026-05-25T00:00:00Z",
                "assets": []
            }
        """.trimIndent()
        // Should not throw on unknown fields (id, url, author, published_at)
        val release = json.decodeFromString<GhRelease>(payload)
        assertEquals("v1.13.0", release.tagName)
    }

    @Test
    fun apkAssetFiltersNonApkAssets() {
        val payload = """
            {
                "tag_name": "v1.14.0",
                "assets": [
                    {"name": "LRReader-v1.14.0.apk", "browser_download_url": "u1", "size": 1, "content_type": "x"},
                    {"name": "checksums.txt", "browser_download_url": "u2", "size": 1, "content_type": "x"},
                    {"name": "source.zip", "browser_download_url": "u3", "size": 1, "content_type": "x"}
                ]
            }
        """.trimIndent()
        val release = json.decodeFromString<GhRelease>(payload)
        assertEquals("LRReader-v1.14.0.apk", release.apkAsset?.name)
    }

    @Test
    fun apkAssetReturnsNullWhenNoApk() {
        val payload = """{"tag_name":"v1.14.0","assets":[{"name":"checksums.txt","browser_download_url":"","size":0,"content_type":""}]}"""
        val release = json.decodeFromString<GhRelease>(payload)
        assertNull(release.apkAsset)
    }

    @Test
    fun versionCodeFromAssetFilename() {
        val payload = """
            {
                "tag_name": "v1.14.0",
                "assets": [{"name": "LRReader-v1.14.0.apk", "browser_download_url": "", "size": 0, "content_type": ""}]
            }
        """.trimIndent()
        val release = json.decodeFromString<GhRelease>(payload)
        assertEquals(11400, release.versionCode)
    }

    @Test
    fun versionCodeFallsBackToTagName() {
        val payload = """{"tag_name":"v2.5.3","assets":[]}"""
        val release = json.decodeFromString<GhRelease>(payload)
        assertEquals(20503, release.versionCode)
    }

    @Test
    fun versionCodeReturnsZeroForUnparseableTag() {
        val payload = """{"tag_name":"nightly-2026-05-26","assets":[]}"""
        val release = json.decodeFromString<GhRelease>(payload)
        assertEquals(0, release.versionCode)
    }

    @Test
    fun versionCodeReturnsZeroForBetaTag() {
        val payload = """{"tag_name":"v1.14.0-beta1","assets":[]}"""
        val release = json.decodeFromString<GhRelease>(payload)
        // Strict pattern by design — beta tags excluded for now (in-app-update.md §5 Asset name regression)
        assertEquals(0, release.versionCode)
    }

    @Test
    fun assetVersionCodeBeatsTagWhenBothPresent() {
        // Asset filename takes precedence if available (more reliable)
        val payload = """
            {
                "tag_name": "v9.9.9",
                "assets": [{"name": "LRReader-v1.14.0.apk", "browser_download_url": "", "size": 0, "content_type": ""}]
            }
        """.trimIndent()
        val release = json.decodeFromString<GhRelease>(payload)
        assertEquals(11400, release.versionCode)
    }
}
