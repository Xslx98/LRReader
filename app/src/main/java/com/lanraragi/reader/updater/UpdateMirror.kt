package com.lanraragi.reader.updater

import com.lanraragi.reader.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The China download mirror for the self-update, `dl.xslx.site`, which serves
 * a copy of the latest GitHub release: `latest.json` (a subset of GitHub's
 * `releases/latest` response, same field names, `.apk` assets only, download
 * URLs rewritten to the mirror) and the APKs themselves. Requests from outside
 * China are redirected to GitHub with the same response shapes.
 *
 * **The mirror is not trusted.** It only saves bandwidth and reachability: an
 * APK it serves is installed only when its size and SHA-256 match the asset's
 * `digest` ([ApkIntegrity.mismatch], a digest is required for mirror bytes)
 * and its signing certificate matches the installed app ([ApkSigner]). A
 * failed or rejected mirror download is retried once from GitHub
 * ([githubDownloadUrl]) against the same size and digest.
 *
 * Rate limits on the mirror (per IP): 6 json and 2 APK requests a minute, one
 * APK connection at a time; excess requests get HTTP 429, which callers treat
 * like any other failure and fall back to GitHub.
 */
internal object UpdateMirror {
    const val HOST = "dl.xslx.site"
    const val PATH_PREFIX = "/lrreader/"
    const val LATEST_JSON_URL = "https://$HOST${PATH_PREFIX}latest.json"

    /** Connect and whole-call timeout of the mirror's latest.json request; GitHub is the fallback. */
    const val JSON_TIMEOUT_SECONDS = 5L

    private const val GITHUB_HOST = "github.com"
    private val GITHUB_DOWNLOAD_SEGMENTS = listOf("Xslx98", "LRReader", "releases", "download")
    // Starts with a letter or digit, so "." and ".." can never become path segments.
    private val SAFE_TAG = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$""")
    private val SAFE_APK_NAME = Regex("""^[A-Za-z0-9._-]{1,100}\.apk$""")

    /** Sent on every request to [HOST]. */
    val userAgent: String get() = "LRReader/${BuildConfig.VERSION_NAME}"

    /** True only for an https URL on exactly [HOST] under [PATH_PREFIX]. */
    fun isMirrorUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && parsed.host == HOST && parsed.encodedPath.startsWith(PATH_PREFIX)
    }

    /**
     * The GitHub release download of [assetName] in [tag], or null when either
     * is not a plain name (so a crafted mirror response cannot steer the
     * fallback anywhere else).
     */
    fun githubDownloadUrl(tag: String, assetName: String): String? {
        if (!SAFE_TAG.matches(tag) || !SAFE_APK_NAME.matches(assetName)) return null
        val builder = HttpUrl.Builder().scheme("https").host(GITHUB_HOST)
        GITHUB_DOWNLOAD_SEGMENTS.forEach { builder.addPathSegment(it) }
        return builder.addPathSegment(tag).addPathSegment(assetName).build().toString()
    }

    /** The GitHub fallback for [release]'s APK when it is served by the mirror, else null. */
    fun fallbackFor(release: GhRelease): String? {
        val asset = release.apkAsset ?: return null
        if (!isMirrorUrl(asset.browserDownloadUrl)) return null
        return githubDownloadUrl(release.tagName, asset.name)
    }
}
