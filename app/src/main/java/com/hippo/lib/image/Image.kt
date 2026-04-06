package com.hippo.lib.image

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
import com.hippo.ehviewer.Analytics
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min


class Image private constructor(
    source: FileInputStream?,
    drawable: Drawable? = null,
    val hardware: Boolean = false,
    val release: () -> Unit? = {},
) {
    private val mDrawableRef = AtomicReference<Drawable?>(null)
    private var mBitmap: Bitmap? = null
    private var mStickerBitmap: Bitmap? = null  // Cached bitmap for non-BitmapDrawable texImage
    private var mReferences = 0

    val animated: Boolean
    val width: Int
    val height: Int

    init {
        source?.let {
            val fileSize = source.channel.size()
            var simpleSize: Int? = null
            if (fileSize > 10485760) {
                simpleSize = (fileSize / 10485760 + 1).toInt()
            }
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
                            val screenSize = min(
                                info.size.width / screenWidth,
                                info.size.height / screenHeight
                            ).coerceAtLeast(1)
                            decoder.setTargetSampleSize(
                                max(screenSize, simpleSize ?: 1)
                            )
                            // Don't
                        }
                    )
                } catch (e: DecodeException) {
                    // ImageDecoder 失败时回退到 BitmapFactory
                    try {
                        // 重置流位置以便重新读取
                        source.channel.position(0)
                        if (simpleSize != null) {
                            val option = BitmapFactory.Options().apply {
                                inSampleSize = simpleSize
                            }
                            val bitmap = BitmapFactory.decodeStream(source, null, option)
                            mDrawableRef.set(
                                bitmap?.toDrawable(Resources.getSystem())
                            )
                        } else {
                            mDrawableRef.set(BitmapDrawable.createFromStream(source, null))
                        }
                    } catch (fallbackException: Exception) {
                        Analytics.recordException(fallbackException)
                        throw Exception("Android 9 解码失败", e)
                    }
                }
                // Should we lazy decode it?
            } else {
                if (simpleSize != null) {
                    val option = BitmapFactory.Options().apply {
                        inSampleSize = simpleSize
                    }
                    val bitmap = BitmapFactory.decodeStream(source, null, option)
                    mDrawableRef.set(
                        BitmapDrawable(Resources.getSystem(), bitmap)
                    )
                } else {
                    mDrawableRef.set(BitmapDrawable.createFromStream(source, null))
                }
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
        drawable.draw(Canvas(bitmap))
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
                    bmp
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
        get() {
            if (animated)
                return 10
            return 0
        }

    @get:SuppressWarnings("deprecation")
    val isOpaque: Boolean
        get() {
            return mDrawableRef.get()?.opacity == PixelFormat.OPAQUE
        }

    companion object {
        private const val TAG = "Image"
        var screenWidth: Int = 0
        var screenHeight: Int = 0

        @JvmStatic
        fun initialize(context: android.content.Context) {
            screenWidth = context.resources.displayMetrics.widthPixels
            screenHeight = context.resources.displayMetrics.heightPixels
        }

        @JvmStatic
        fun decode(stream: FileInputStream, hardware: Boolean = true): Image? {
            try {
                return Image(stream, hardware = hardware)
            } catch (e: Exception) {
                e.printStackTrace()
                Analytics.recordException(e)
                return null
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
        private external fun nativeRender(
            bitmap: Bitmap,
            srcX: Int, srcY: Int, dst: Bitmap, dstX: Int, dstY: Int,
            width: Int, height: Int,
        )

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
