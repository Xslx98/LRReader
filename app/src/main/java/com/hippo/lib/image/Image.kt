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
import androidx.core.graphics.drawable.toDrawable
import com.hippo.ehviewer.EhApplication
import java.io.FileInputStream
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.createBitmap
import com.hippo.ehviewer.Analytics


class Image private constructor(
    source: FileInputStream?,
    drawable: Drawable? = null,
    val hardware: Boolean = false,
    val release: () -> Unit? = {},
) {
    private var mObtainedDrawable: Drawable?
    private var mBitmap: Bitmap? = null
    private var mStickerBitmap: Bitmap? = null  // Cached bitmap for non-BitmapDrawable texImage
    private var mReferences = 0

    init {
        mObtainedDrawable = null
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
                    mObtainedDrawable =
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
                            mObtainedDrawable =
                                bitmap?.toDrawable(EhApplication.getInstance().resources)
                        } else {
                            mObtainedDrawable = BitmapDrawable.createFromStream(source, null)
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
                    mObtainedDrawable =
                        BitmapDrawable(EhApplication.getInstance().resources, bitmap)
                } else {
                    mObtainedDrawable = BitmapDrawable.createFromStream(source, null)
                }
            }
        }
        if (mObtainedDrawable == null) {
            mObtainedDrawable = drawable!!
//            throw IllegalArgumentException("数据解码出错")
        }
    }

    val animated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        mObtainedDrawable is AnimatedImageDrawable
    } else {
        mObtainedDrawable is AnimationDrawable
    }
    val width =
        (mObtainedDrawable as? BitmapDrawable)?.bitmap?.width ?: mObtainedDrawable!!.intrinsicWidth
    val height = (mObtainedDrawable as? BitmapDrawable)?.bitmap?.height
        ?: mObtainedDrawable!!.intrinsicHeight
    val isRecycled: Boolean
        get() = mObtainedDrawable == null

    private var started = false

    @Synchronized
    fun recycle() {
        if (mObtainedDrawable == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (mObtainedDrawable is AnimatedImageDrawable) {
                (mObtainedDrawable as AnimatedImageDrawable?)?.stop()
            }
        }
        if (mObtainedDrawable is BitmapDrawable) {
            (mObtainedDrawable as BitmapDrawable?)?.bitmap?.recycle()
        }
        mObtainedDrawable?.callback = null
        mObtainedDrawable = null
        mBitmap?.recycle()
        mBitmap = null
        mStickerBitmap?.recycle()
        mStickerBitmap = null
        release()
    }

    private fun prepareBitmap() {
        if (mBitmap != null) return
        mBitmap = createBitmap(width, height)
    }

    private fun updateBitmap() {
        prepareBitmap()
        mObtainedDrawable!!.draw(Canvas(mBitmap!!))
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

    fun getDrawable(): Drawable {
        check(obtain()) { "Recycled!" }
        return mObtainedDrawable as Drawable
    }

    @Synchronized
    fun texImage(init: Boolean, offsetX: Int, offsetY: Int, width: Int, height: Int) {
        check(!hardware) { "Hardware buffer cannot be used in glgallery" }
        try {
            val drawable = mObtainedDrawable ?: return  // Recycled — bail out
            val bitmap: Bitmap = if (animated) {
                updateBitmap()
                mBitmap!!
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
                (mObtainedDrawable as AnimatedImageDrawable?)?.start()
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
            return mObtainedDrawable?.opacity == PixelFormat.OPAQUE
        }

    companion object {
        var screenWidth: Int = 0
        var screenHeight: Int = 0

        @JvmStatic
        fun initialize(ehApplication: EhApplication) {
            screenWidth = ehApplication.resources.displayMetrics.widthPixels
            screenHeight = ehApplication.resources.displayMetrics.heightPixels
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