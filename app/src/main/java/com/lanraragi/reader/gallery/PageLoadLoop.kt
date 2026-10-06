package com.lanraragi.reader.gallery

import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import com.lanraragi.framework.lib.image.DecodeResult
import com.lanraragi.reader.R
import java.io.File

/**
 * Page image formats recognised by their magic bytes
 * ([ReaderPageCache.detectImageFormat]). A recognised format is a valid page
 * file, but not every format decodes on every device: AVIF needs API 31
 * (minSdk is 28) and the platform has no JPEG XL decoder at all.
 */
internal enum class PageImageFormat(val displayName: String, private val minSdk: Int) {
    JPEG("JPEG", 0),
    PNG("PNG", 0),
    GIF("GIF", 0),
    WEBP("WebP", 0),
    BMP("BMP", 0),
    HEIF("HEIF", 0),
    AVIF("AVIF", Build.VERSION_CODES.S),
    JXL("JPEG XL", Int.MAX_VALUE),
    ;

    fun decodableOn(sdkInt: Int): Boolean = sdkInt >= minSdk
}

/** Why a reader page could not be shown; [message] is the page error. */
internal sealed class PageFailure(@param:StringRes private val messageRes: Int) {

    open fun message(context: Context): String = context.getString(messageRes)

    /** The fetched file is missing or below [ReaderPageCache.MIN_IMAGE_SIZE]. */
    data object DownloadInvalid : PageFailure(R.string.lrr_download_failed_invalid)

    /** The fetched file has no known image magic. */
    data object NotImage : PageFailure(R.string.lrr_download_failed_not_image)

    /** A valid file in a format this device cannot decode; deleting or re-fetching it cannot help. */
    data class Unsupported(val format: PageImageFormat) : PageFailure(R.string.lrr_decode_unsupported_format) {
        override fun message(context: Context): String =
            context.getString(R.string.lrr_decode_unsupported_format, format.displayName)
    }

    /** A valid file that ran out of memory at both decode samples. */
    data object TooLarge : PageFailure(R.string.lrr_decode_too_large)

    /** A known, decodable format that the decoder rejected: damaged bytes. */
    data object Corrupt : PageFailure(R.string.lrr_decode_failed)

    companion object {
        /**
         * Classify a failed decode of a file of [format] (audit 2026-10-06d
         * PERF-01). Only [Corrupt] means the file itself is bad.
         */
        fun ofDecode(result: DecodeResult<*>, format: PageImageFormat?, sdkInt: Int): PageFailure = when {
            result is DecodeResult.OutOfMemory -> TooLarge
            format != null && !format.decodableOn(sdkInt) -> Unsupported(format)
            else -> Corrupt
        }
    }
}

/**
 * A fetched page did not decode, with the reason; thrown by tank member
 * sources so the composite reader can show it. Not an IOException: it is not
 * a fetch failure.
 */
internal class PageDecodeException(val failure: PageFailure) :
    Exception("Page did not decode: ${failure::class.java.simpleName}")

/** Result of [PageLoadLoop.run]. */
internal sealed interface PageLoadResult<out T> {
    data class Loaded<T>(val value: T) : PageLoadResult<T>
    data class Failed(val failure: PageFailure) : PageLoadResult<Nothing>
}

/**
 * Fetch -> validate -> decode for one reader page, with one retry that
 * deletes the file and fetches it again — but only when the FILE is bad
 * (audit 2026-10-06d PERF-01). A valid page the device cannot decode
 * (unsupported format, out of memory twice) fails at once and is kept: a
 * re-download would bring the same bytes back. A damaged page in the
 * download directory ([ownedByDownload], hybrid mode) is never deleted on a
 * decode failure either; the download worker owns that file. It is handed
 * to the download side instead ([handOverDamaged], audit 2026-10-06e P4-d),
 * which also points [pageFile] at the reader cache, so the retry fetches a
 * fresh copy there.
 *
 * @param pageFile where the page lives now (may move from the reader cache
 *   to the download dir once the page list is known, and back to the reader
 *   cache once a damaged download-dir page was handed over).
 * @param fetch downloads the page into [pageFile] unless it is already there.
 * @param handOverDamaged gives a damaged download-dir page to the download
 *   pipeline for a re-download; true when [pageFile] now resolves elsewhere.
 */
internal class PageLoadLoop<T>(
    private val pageFile: () -> File,
    private val fetch: suspend () -> Unit,
    private val decode: suspend (File) -> DecodeResult<T>,
    private val ownedByDownload: (File) -> Boolean,
    private val handOverDamaged: (File) -> Boolean,
    private val retryDelay: suspend () -> Unit,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {

    suspend fun run(): PageLoadResult<T> {
        for (attempt in 0 until ATTEMPTS) {
            if (attempt > 0) {
                retryDelay()
                val stale = pageFile()
                if (stale.exists()) stale.delete()
            }
            attemptOnce(isLast = attempt == ATTEMPTS - 1)?.let { return it }
        }
        // Unreachable: the last attempt never asks for a retry.
        return PageLoadResult.Failed(PageFailure.Corrupt)
    }

    /** One fetch + decode; null asks for a retry (never on the last attempt). */
    private suspend fun attemptOnce(isLast: Boolean): PageLoadResult<T>? {
        fetch()
        val file = pageFile()
        if (!file.exists() || file.length() < ReaderPageCache.MIN_IMAGE_SIZE) {
            return failOrRetry(isLast, PageFailure.DownloadInvalid)
        }
        val format = ReaderPageCache.detectImageFormat(file)
        if (format == null) {
            file.delete()
            return failOrRetry(isLast, PageFailure.NotImage)
        }
        return when (val decoded = decode(file)) {
            is DecodeResult.Ok -> PageLoadResult.Loaded(decoded.value)
            else -> {
                val failure = PageFailure.ofDecode(decoded, format, sdkInt)
                when {
                    failure !is PageFailure.Corrupt -> PageLoadResult.Failed(failure)
                    !ownedByDownload(file) -> {
                        file.delete()
                        failOrRetry(isLast, failure)
                    }
                    // Not deleted: the download pipeline replaces it.
                    handOverDamaged(file) -> failOrRetry(isLast, failure)
                    else -> PageLoadResult.Failed(failure)
                }
            }
        }
    }

    private fun failOrRetry(isLast: Boolean, failure: PageFailure): PageLoadResult<T>? =
        if (isLast) PageLoadResult.Failed(failure) else null

    private companion object {
        /** Once normally, once after deleting a bad file. */
        const val ATTEMPTS = 2
    }
}
