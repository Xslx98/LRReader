package com.lanraragi.reader.download

import android.content.res.Resources
import androidx.annotation.StringRes
import com.lanraragi.reader.R

/** Short user-facing label for [this] reason, or null for [DownloadFailureReason.UNKNOWN]. */
@StringRes
fun DownloadFailureReason.labelRes(): Int? = when (this) {
    DownloadFailureReason.NO_SPACE -> R.string.download_failure_no_space
    DownloadFailureReason.AUTH -> R.string.download_failure_auth
    DownloadFailureReason.NOT_FOUND -> R.string.download_failure_not_found
    DownloadFailureReason.SERVER -> R.string.download_failure_server
    DownloadFailureReason.NETWORK -> R.string.download_failure_network
    DownloadFailureReason.CORRUPT -> R.string.download_failure_corrupt
    DownloadFailureReason.UNKNOWN -> null
}

/**
 * [base] followed by the label of [reason] ("Failed · Storage full"), or
 * [base] unchanged when there is no known reason (audit 2026-10-04 C21).
 */
fun withFailureReason(resources: Resources, base: String, reason: DownloadFailureReason?): String {
    val label = reason?.labelRes()
    return if (label == null) base else resources.getString(R.string.download_failure_with_reason, base, resources.getString(label))
}
