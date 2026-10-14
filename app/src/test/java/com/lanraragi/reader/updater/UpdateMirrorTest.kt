package com.lanraragi.reader.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The China mirror's URL rules and its GitHub fallback. */
class UpdateMirrorTest {

    private fun release(tag: String, name: String, url: String) = GhRelease(
        tagName = tag,
        assets = listOf(GhReleaseAsset(name = name, browserDownloadUrl = url)),
    )

    @Test
    fun githubDownloadUrlIsTheReleaseAssetOfThisRepository() {
        val url = UpdateMirror.githubDownloadUrl("v2.1.0", "LRReader-v2.1.0-arm64.apk")
        assertEquals("https://github.com/Xslx98/LRReader/releases/download/v2.1.0/LRReader-v2.1.0-arm64.apk", url)
        assertTrue(ApkIntegrity.isTrustedUrl(url!!))
    }

    @Test
    fun githubDownloadUrlRejectsNamesThatCouldLeaveTheReleaseDirectory() {
        assertNull(UpdateMirror.githubDownloadUrl("..", "LRReader-v2.1.0-arm64.apk"))
        assertNull(UpdateMirror.githubDownloadUrl("v2.1.0/../../x", "a.apk"))
        assertNull(UpdateMirror.githubDownloadUrl("", "a.apk"))
        assertNull(UpdateMirror.githubDownloadUrl("v2.1.0", "../a.apk"))
        assertNull(UpdateMirror.githubDownloadUrl("v2.1.0", "payload.so"))
    }

    @Test
    fun aMirrorServedApkFallsBackToGitHub() {
        val mirrored = release("v2.1.0", "LRReader-v2.1.0-arm64.apk", "https://dl.xslx.site/lrreader/LRReader-v2.1.0-arm64.apk")
        assertEquals(
            "https://github.com/Xslx98/LRReader/releases/download/v2.1.0/LRReader-v2.1.0-arm64.apk",
            UpdateMirror.fallbackFor(mirrored),
        )
    }

    @Test
    fun aGitHubServedApkHasNoFallback() {
        val direct = release(
            "v2.1.0", "LRReader-v2.1.0-arm64.apk",
            "https://github.com/Xslx98/LRReader/releases/download/v2.1.0/LRReader-v2.1.0-arm64.apk",
        )
        assertNull(UpdateMirror.fallbackFor(direct))
        assertNull(UpdateMirror.fallbackFor(GhRelease(tagName = "v2.1.0")))
    }
}
