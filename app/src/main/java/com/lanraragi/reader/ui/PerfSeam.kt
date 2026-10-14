package com.lanraragi.reader.ui

import android.content.Intent
import com.lanraragi.reader.BuildConfig

/**
 * Benchmark-only entry: `:benchmark` launches the app with
 * `--es lrr.perf.server http://127.0.0.1:3939` so a cold-start iteration
 * lands on the list instead of the first-run server form. The whole path is
 * behind [BuildConfig.PERF_SEAM], a constant `false` in every shipped
 * variant (R8 removes the branch), `true` only in the build types the
 * baselineprofile plugin creates. Never read the extra anywhere else.
 */
object PerfSeam {
    const val EXTRA_SERVER_URL = "lrr.perf.server"

    /** The server URL to auto-connect to, or null when the seam is off / absent. */
    fun serverUrlFrom(intent: Intent?, enabled: Boolean = BuildConfig.PERF_SEAM): String? {
        if (!enabled) return null
        val url = intent?.getStringExtra(EXTRA_SERVER_URL)?.trim().orEmpty()
        val lower = url.lowercase()
        return if (lower.startsWith("http://") || lower.startsWith("https://")) url else null
    }
}
