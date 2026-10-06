package com.lanraragi.framework.lib.image

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.ImageDecoder.ALLOCATOR_DEFAULT
import android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
import android.graphics.ImageDecoder.DecodeException
import android.graphics.ImageDecoder.ImageInfo
import android.graphics.ImageDecoder.Source
import android.graphics.PixelFormat
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import com.lanraragi.reader.Analytics
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min


class Image private constructor(
    source: FileInputStream?,
    drawable: Drawable? = null,
    val hardware: Boolean = false,
    targetWidth: Int = 0,
    targetHeight: Int = 0,
    /** Extra sampling factor; 2 on the retry after an OutOfMemoryError (audit C11). */
    sampleMultiplier: Int = 1,
    /** Reader fit for no-target decodes, read once per [decode] call. */
    pageFit: PageFit = PageFit.FIT,
    val release: () -> Unit? = {},
) {
    private val mDrawableRef = AtomicReference<Drawable?>(null)
    private var mBitmap: Bitmap? = null
    private var mStickerBitmap: Bitmap? = null  // Cached bitmap for non-BitmapDrawable texImage
    private var mUploadCopy: Bitmap? = null  // ARGB_8888 copy of an F16/1010102/565 page (audit C14)
    private var mReferences = 0

    // Animated pages (audit 2026-10-04 C13): one software render per frame, not
    // one per uploaded tile, at the drawable's own frame interval.
    private val mFrameDirty = java.util.concurrent.atomic.AtomicBoolean(true)
    private var mCanvas: Canvas? = null

    @Volatile
    private var mNextFrameDelayMs: Long = DEFAULT_FRAME_DELAY_MS

    val animated: Boolean
    val width: Int
    val height: Int

    /**
     * Native bytes this page keeps resident, for the reader cache budget (audit
     * PERF-07): the decoded bitmap plus the ARGB_8888 upload copy [texImage]
     * makes for other configs (an RGBA_F16 page holds 8 + 4 bytes per pixel).
     * Fixed at decode, so the LRU sees the same size on put and on remove.
     */
    val byteCount: Int

    init {
        source?.let {
            val fileSize = source.channel.size()
            // Sampling depends on the pixel size and the target only (audit
            // 2026-10-06c PERF-01): the old EhViewer floor of fileSize / 10 MiB + 1
            // halved large lossless pages that already fit the screen.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val src = ImageDecoder.createSource(
                    source.channel.map(
                        FileChannel.MapMode.READ_ONLY, 0,
                        fileSize
                    )
                )
                try {
                    mDrawableRef.set(
                        ImageDecoder.decodeDrawable(src) { decoder: ImageDecoder, info: ImageInfo, _: Source ->
                            decoder.allocator =
                                if (hardware) ALLOCATOR_DEFAULT else ALLOCATOR_SOFTWARE
                            // Sadly we must use software memory since we need copy it to tile buffer, fuck glgallery
                            // Idk it will cause how much performance regression
                            // Thumbnail loads pass an explicit target; the reader
                            // passes none and samples by its scale mode.
                            val sampleSize = computeDecodeSampleSize(
                                info.size.width, info.size.height,
                                targetWidth, targetHeight, sampleMultiplier, pageFit
                            )
                            if (Log.isLoggable(TAG, Log.DEBUG)) {
                                Log.d(
                                    TAG,
                                    "decode ${info.size.width}x${info.size.height}" +
                                        " sample=$sampleSize" +
                                        " target=${targetWidth}x$targetHeight fit=$pageFit"
                                )
                            }
                            decoder.setTargetSampleSize(sampleSize)
                            // Don't
                        }
                    )
                } catch (e: DecodeException) {
                    // ImageDecoder 失败时回退到 BitmapFactory
                    try {
                        // 重置流位置以便重新读取
                        source.channel.position(0)
                        val bitmap = decodeSampledBitmap(
                            source, targetWidth, targetHeight, sampleMultiplier, pageFit
                        )
                        mDrawableRef.set(bitmap?.toDrawable(Resources.getSystem()))
                    } catch (fallbackException: Exception) {
                        Analytics.recordException(fallbackException)
                        throw Exception("Android 9 解码失败", e)
                    }
                }
                // Should we lazy decode it?
            } else {
                val bitmap = decodeSampledBitmap(
                    source, targetWidth, targetHeight, sampleMultiplier, pageFit
                )
                mDrawableRef.set(bitmap?.toDrawable(Resources.getSystem()))
            }
        }
        if (mDrawableRef.get() == null) {
            mDrawableRef.set(
                checkNotNull(drawable) { "Image decode failed and no fallback drawable" }
            )
        }

        // Compute immutable properties from the guaranteed non-null drawable
        val initDrawable = checkNotNull(mDrawableRef.get()) {
            "Image has no drawable after initialization"
        }
        animated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            initDrawable is AnimatedImageDrawable
        } else {
            initDrawable is AnimationDrawable
        }
        width = (initDrawable as? BitmapDrawable)?.bitmap?.width
            ?: initDrawable.intrinsicWidth
        height = (initDrawable as? BitmapDrawable)?.bitmap?.height
            ?: initDrawable.intrinsicHeight
        val bitmap = (initDrawable as? BitmapDrawable)?.bitmap
        byteCount = if (bitmap == null) {
            width * height * ARGB_8888_BYTES
        } else {
            residentBytes(width, height, bitmap.allocationByteCount, bitmap.config)
        }
        if (animated) initDrawable.callback = FrameClock()
    }

    val isRecycled: Boolean
        get() = mDrawableRef.get() == null

    private var started = false

    @Synchronized
    fun recycle() {
        val drawable = mDrawableRef.getAndSet(null) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (drawable as? AnimatedImageDrawable)?.stop()
        }
        if (drawable is BitmapDrawable) {
            drawable.bitmap?.recycle()
        }
        drawable.callback = null
        mBitmap?.recycle()
        mBitmap = null
        mStickerBitmap?.recycle()
        mStickerBitmap = null
        mCanvas = null
        mUploadCopy?.recycle()
        mUploadCopy = null
        release()
    }

    private fun prepareBitmap() {
        if (mBitmap != null) return
        try {
            mBitmap = createBitmap(width, height)
        } catch (e: OutOfMemoryError) {
            mBitmap = null
            Log.e(TAG, "OOM creating bitmap ${width}x${height}")
        }
    }

    private fun updateBitmap() {
        prepareBitmap()
        val bitmap = mBitmap ?: return
        val drawable = mDrawableRef.get() ?: return
        if (!mFrameDirty.getAndSet(false)) return
        val canvas = mCanvas ?: Canvas(bitmap).also { mCanvas = it }
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        drawable.draw(canvas)
    }

    /** The animation loop moved to the next frame; the next tile upload re-renders it. */
    fun advanceFrame() {
        mFrameDirty.set(true)
    }

    /**
     * On a software canvas AnimatedImageDrawable schedules its own next frame via
     * scheduleSelf; capturing that time gives the real frame interval (it was a
     * hard-coded 10 ms, redrawing ~100 times a second).
     */
    private inner class FrameClock : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) = Unit

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            mNextFrameDelayMs = `when` - android.os.SystemClock.uptimeMillis()
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
    }

    @Synchronized
    fun obtain(): Boolean {
        return if (isRecycled) {
            false
        } else {
            ++mReferences
            true
        }
    }

    @Synchronized
    fun release() {
        --mReferences
        if (mReferences <= 0 && !isRecycled) {
            recycle()
        }
    }

    @Synchronized
    fun getDrawable(): Drawable {
        check(obtain()) { "Recycled!" }
        return checkNotNull(mDrawableRef.get()) {
            "Drawable became null after obtain succeeded"
        }
    }

    @Synchronized
    fun texImage(init: Boolean, offsetX: Int, offsetY: Int, width: Int, height: Int) {
        check(!hardware) { "Hardware buffer cannot be used in glgallery" }
        try {
            val drawable = mDrawableRef.get() ?: return  // Recycled — bail out
            val bitmap: Bitmap = if (animated) {
                updateBitmap()
                mBitmap ?: return
            } else {
                if (drawable is BitmapDrawable) {
                    val bmp = drawable.bitmap
                    if (bmp == null || bmp.isRecycled) return  // Bitmap already recycled
                    if (needsArgb8888Copy(bmp.config)) {
                        // The native upload copies 4 bytes per pixel; 16-bit PNG or
                        // 10-bit AVIF/HEIF pages decode to other configs.
                        mUploadCopy?.takeIf { !it.isRecycled }
                            ?: bmp.copy(Bitmap.Config.ARGB_8888, false).also { mUploadCopy = it }
                            ?: return
                    } else {
                        bmp
                    }
                } else {
                    // Cache the sticker bitmap to avoid re-creating per tile
                    var cached = mStickerBitmap
                    if (cached == null || cached.isRecycled) {
                        cached = createBitmap(
                            drawable.intrinsicWidth,
                            drawable.intrinsicHeight
                        )
                        mStickerBitmap = cached
                    }
                    val canvas = Canvas(cached)
                    drawable.setBounds(0, 0, cached.width, cached.height)
                    drawable.draw(canvas)
                    cached
                }
            }
            nativeTexImage(
                bitmap,
                init,
                offsetX,
                offsetY,
                width,
                height
            )
        } catch (e: Exception) {
            // Catch all exceptions (ClassCastException, NPE from race, etc.)
            Analytics.recordException(e)
            return
        }
    }

    fun start() {
        if (!started) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                (mDrawableRef.get() as? AnimatedImageDrawable)?.start()
            }
        }
    }

    val delay: Int
        get() = if (animated) frameDelay(mNextFrameDelayMs) else 0

    @get:SuppressWarnings("deprecation")
    val isOpaque: Boolean
        get() {
            return mDrawableRef.get()?.opacity == PixelFormat.OPAQUE
        }

    companion object {
        private const val TAG = "Image"
        internal const val OOM_RETRY_MULTIPLIER = 2

        /** Bitmaps the native tile upload cannot read directly (it requires RGBA_8888). */
        internal fun needsArgb8888Copy(config: Bitmap.Config?): Boolean = config != Bitmap.Config.ARGB_8888

        private const val ARGB_8888_BYTES = 4

        /** See [byteCount]: [sourceBytes] plus a 4-byte-per-pixel upload copy when one is needed. */
        internal fun residentBytes(width: Int, height: Int, sourceBytes: Int, config: Bitmap.Config?): Int {
            val uploadCopy = width * height * ARGB_8888_BYTES
            return if (needsArgb8888Copy(config)) sourceBytes + uploadCopy else sourceBytes
        }

        internal const val DEFAULT_FRAME_DELAY_MS = 100L
        internal const val MIN_FRAME_DELAY_MS = 16L
        internal const val MAX_FRAME_DELAY_MS = 1000L

        /** Next-frame wait for an animated page: the drawable's interval, at most 60 fps. */
        internal fun frameDelay(scheduledMs: Long): Int =
            scheduledMs.coerceIn(MIN_FRAME_DELAY_MS, MAX_FRAME_DELAY_MS).toInt()
        var screenWidth: Int = 0
        var screenHeight: Int = 0

        init {
            // Self-load: nativeTexImage is an external fun and
            // JNI binds lazily on first call — without this, the GL reader
            // crashes with UnsatisfiedLinkError on the GLThread (caught by AVD
            // smoke after the eager Native.initialize() loader was removed).
            try {
                System.loadLibrary("lrreader")
            } catch (e: UnsatisfiedLinkError) {
                // JVM unit tests have no native libs; on device a real load
                // failure resurfaces at the first nativeTexImage call.
                Log.w(TAG, "liblrreader load failed")
            }
        }

        /**
         * Screen size for reader sampling. Called synchronously at boot and again on
         * every configuration change (audit 2026-10-04 C11: it used to run async, so
         * early decodes saw 0x0 and decoded at full size, and it never followed
         * rotation or fold/unfold). Reads the display, not the application
         * resources, whose configuration is frozen at process start.
         */
        @JvmStatic
        fun initialize(context: android.content.Context) {
            val metrics = android.util.DisplayMetrics()
            val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            if (display != null) {
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
            } else {
                metrics.setTo(context.resources.displayMetrics)
            }
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }

        /**
         * Largest integer sample size that keeps both decoded dimensions at or
         * above the target (quality floor). Non-positive targets disable
         * sampling. Pure math — unit tested on the JVM.
         */
        @JvmStatic
        fun computeSampleSize(
            srcWidth: Int,
            srcHeight: Int,
            targetWidth: Int,
            targetHeight: Int,
        ): Int {
            if (targetWidth <= 0 || targetHeight <= 0) return 1
            return min(srcWidth / targetWidth, srcHeight / targetHeight)
                .coerceAtLeast(1)
        }

        /**
         * Effective reader fit for decodes without a target. ReadingSettings
         * installs a source that follows the reading direction and scale mode;
         * until then (and in JVM tests) pages are sampled for FIT, the default.
         */
        @Volatile
        @JvmStatic
        var pageFitSource: () -> PageFit = { PageFit.FIT }

        /**
         * Reader page sample by scale mode (owner decision 2026-10-06, audits
         * R1 / PERF-02): the decoded page is never smaller than what [fit]
         * shows at 1x zoom, in EITHER orientation of the screen, so a rotation
         * handled in place (decoded pages are kept) never shows a page below
         * its 1x size.
         * - FIT: the page fits inside the screen -> the larger ratio; the
         *   smaller of the portrait and landscape samples.
         * - FIT_WIDTH (pager fit width, every top-to-bottom page): the width
         *   ratio against the longer screen side. Tall strips keep full width
         *   whatever their height (the earlier "strips stay sharp" ruling);
         *   wide pages are bounded.
         * - FIT_HEIGHT: the height ratio against the longer screen side.
         * - ORIGIN (origin / fixed scale): the page is drawn 1:1 from the
         *   decoded bitmap, so the old rule stays: both decoded sides at or
         *   above the current screen ([computeSampleSize]).
         * Pinch zoom above 1x magnifies the decoded bitmap; nothing re-decodes.
         * A non-positive screen size disables sampling.
         */
        @JvmStatic
        internal fun readerSampleSize(
            srcWidth: Int,
            srcHeight: Int,
            screenW: Int,
            screenH: Int,
            fit: PageFit,
        ): Int {
            if (screenW <= 0 || screenH <= 0) return 1
            val longSide = max(screenW, screenH)
            val sample = when (fit) {
                PageFit.FIT -> min(
                    max(srcWidth / screenW, srcHeight / screenH),
                    max(srcWidth / screenH, srcHeight / screenW)
                )
                PageFit.FIT_WIDTH -> srcWidth / longSide
                PageFit.FIT_HEIGHT -> srcHeight / longSide
                PageFit.ORIGIN -> computeSampleSize(srcWidth, srcHeight, screenW, screenH)
            }
            return sample.coerceAtLeast(1)
        }

        /**
         * Sample size for one decode: the thumbnail-target sample, or for the
         * reader (no target) [readerSampleSize] for [fit], times
         * [sampleMultiplier] (2 on the OOM retry). Deliberately ignores the file
         * size (audit 2026-10-06c PERF-01); there is no pixel cap, the fit plus
         * the OOM retry bound memory.
         */
        @JvmStatic
        internal fun computeDecodeSampleSize(
            srcWidth: Int,
            srcHeight: Int,
            targetWidth: Int,
            targetHeight: Int,
            sampleMultiplier: Int,
            fit: PageFit = pageFitSource(),
        ): Int {
            val sample = if (targetWidth > 0 && targetHeight > 0) {
                computeSampleSize(srcWidth, srcHeight, targetWidth, targetHeight)
            } else {
                readerSampleSize(srcWidth, srcHeight, screenWidth, screenHeight, fit)
            }
            return sample * sampleMultiplier.coerceAtLeast(1)
        }

        /**
         * BitmapFactory decode (ImageDecoder fallback) with the same sampling as
         * the main path: bounds first, then [computeDecodeSampleSize].
         */
        private fun decodeSampledBitmap(
            source: FileInputStream,
            targetWidth: Int,
            targetHeight: Int,
            sampleMultiplier: Int,
            pageFit: PageFit,
        ): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(source, null, bounds)
            source.channel.position(0)
            val option = BitmapFactory.Options().apply {
                inSampleSize = computeDecodeSampleSize(
                    bounds.outWidth, bounds.outHeight,
                    targetWidth, targetHeight, sampleMultiplier, pageFit
                )
            }
            return BitmapFactory.decodeStream(source, null, option)
        }

        /** Reader page decode: sampled by the reader's scale mode ([pageFitSource]). */
        @JvmStatic
        fun decode(stream: FileInputStream, hardware: Boolean = true): Image? {
            val fit = pageFitSource()
            return retryOnOutOfMemory(rewind = { stream.channel.position(0) }) { multiplier ->
                Image(stream, hardware = hardware, sampleMultiplier = multiplier, pageFit = fit)
            }
        }

        /**
         * Decode with a target-size hint: the result is sampled so both
         * dimensions stay >= the target. Used by thumbnail loads (Conaco);
         * the reader path uses the no-hint overload above.
         */
        @JvmStatic
        fun decode(
            stream: FileInputStream,
            hardware: Boolean,
            targetWidth: Int,
            targetHeight: Int,
        ): Image? {
            return retryOnOutOfMemory(rewind = { stream.channel.position(0) }) { multiplier ->
                Image(
                    stream,
                    hardware = hardware,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    sampleMultiplier = multiplier,
                    // Conaco loads without a target keep the old screen rule
                    // (both sides >= the screen), not a reader scale mode.
                    pageFit = PageFit.ORIGIN,
                )
            }
        }

        /**
         * Runs [attempt] at sample multiplier 1; on OutOfMemoryError rewinds and
         * retries once at [OOM_RETRY_MULTIPLIER] (audit 2026-10-04 C11: no general
         * pixel cap; since 2026-10-06 the scale-mode sample of [readerSampleSize]
         * bounds pages that are large in one dimension). A second OOM or any exception yields null, which
         * callers already show as "decode failed" instead of a spinner forever.
         * [decodeResult] keeps the reason instead.
         */
        internal fun <T> retryOnOutOfMemory(rewind: () -> Unit, attempt: (Int) -> T): T? =
            (decodeWithOomRetry(rewind, attempt) as? DecodeResult.Ok)?.value

        /**
         * Reader-page decode like [decode] that reports WHY it failed
         * ([DecodeResult.OutOfMemory] vs [DecodeResult.Failed]) so a valid page
         * the device cannot show is not mistaken for a damaged file (audit
         * 2026-10-06d PERF-01).
         */
        fun decodeResult(stream: FileInputStream, hardware: Boolean = true): DecodeResult<Image> {
            // Sampled by the reader's scale mode, read once, like [decode].
            val fit = pageFitSource()
            return decodeWithOomRetry(rewind = { stream.channel.position(0) }) { multiplier ->
                Image(stream, hardware = hardware, sampleMultiplier = multiplier, pageFit = fit)
            }
        }

        @JvmStatic
        fun decode(drawable: Drawable?, hardware: Boolean = true): Image? {
            try {
                return Image(null, drawable, hardware = hardware)
            } catch (e: Exception) {
                e.printStackTrace()
                Analytics.recordException(e)
                return null
            }
        }

//        @JvmStatic
//        fun decode(buffer: ByteBuffer, hardware: Boolean = true, release: () -> Unit? = {}): Image {
//            val src = ImageDecoder.createSource(buffer)
//            return Image(src, hardware = hardware) {
//                release()
//            }
//        }

        @JvmStatic
        fun create(bitmap: Bitmap): Image? {
            try {
                return Image(null, bitmap.toDrawable(Resources.getSystem()), false)
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }

        @JvmStatic
        private external fun nativeTexImage(
            bitmap: Bitmap,
            init: Boolean,
            offsetX: Int,
            offsetY: Int,
            width: Int,
            height: Int,
        )
    }
}
