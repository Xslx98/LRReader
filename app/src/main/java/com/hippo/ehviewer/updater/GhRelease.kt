package com.hippo.ehviewer.updater

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GitHub Releases API minimal model. Fields not used by LR Reader are ignored
 * via `Json { ignoreUnknownKeys = true }` at the parse site.
 *
 * API: GET https://api.github.com/repos/Xslx98/LRReader/releases/latest
 * Docs: https://docs.github.com/en/rest/releases/releases#get-the-latest-release
 */
@Serializable
data class GhRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("body") val body: String = "",
    // GitHub always sends this; default to emptyList so partial/proxy responses
    // degrade gracefully — apkAsset → null, versionCode falls back to tagName.
    @SerialName("assets") val assets: List<GhReleaseAsset> = emptyList(),
) {

    /**
     * The first .apk asset (LR Reader release flow uploads exactly one APK per release).
     * Returns null if no apk asset exists.
     */
    val apkAsset: GhReleaseAsset?
        get() = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

    /**
     * Parse `versionCode` from the asset filename if possible
     * (`LRReader-vMAJOR.MINOR.PATCH.apk` → `MAJOR*10000 + MINOR*100 + PATCH`).
     * Falls back to parsing [tagName] (`vMAJOR.MINOR.PATCH`).
     * Returns 0 if neither pattern matches (caller should treat as "do not update").
     */
    val versionCode: Int
        get() {
            apkAsset?.let { asset ->
                ASSET_PATTERN.matchEntire(asset.name)?.let { return computeVersionCode(it) }
            }
            TAG_PATTERN.matchEntire(tagName)?.let { return computeVersionCode(it) }
            return 0
        }

    private fun computeVersionCode(match: MatchResult): Int {
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toInt()
        val patch = match.groupValues[3].toInt()
        return major * 10_000 + minor * 100 + patch
    }

    companion object {
        // LRReader-v1.13.0.apk
        private val ASSET_PATTERN = Regex("""^LRReader-v(\d+)\.(\d+)\.(\d+)\.apk$""")
        // v1.13.0
        private val TAG_PATTERN = Regex("""^v(\d+)\.(\d+)\.(\d+)$""")
    }
}

@Serializable
data class GhReleaseAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    @SerialName("size") val size: Long = 0L,
    @SerialName("content_type") val contentType: String = "",
)
