/*
 * Copyright 2015-2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lanraragi.framework.widget

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.util.AttributeSet
import android.view.View
import androidx.annotation.IntDef
import androidx.core.content.ContextCompat
import coil3.request.Disposable
import com.lanraragi.framework.drawable.PreciselyClipDrawable
import com.lanraragi.reader.R
import com.lanraragi.reader.cover.CoverLoader
import com.lanraragi.reader.cover.CoverStore

open class LoadImageView : FixedAspectImageView, CoverLoader.Callback,
    View.OnClickListener, View.OnLongClickListener, Animatable {

    private var mRequest: Disposable? = null
    private var mKey: String? = null
    private var mUrl: String? = null
    private var mUseNetwork = false
    private var mOffsetX = Int.MIN_VALUE
    private var mOffsetY = Int.MIN_VALUE
    private var mClipWidth = Int.MIN_VALUE
    private var mClipHeight = Int.MIN_VALUE
    private var mRetryType = 0

    @JvmField
    var mFailed = false
    private var mLoadFromDrawable = false

    constructor(context: Context) : super(context) {
        init(context, null, 0)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context, attrs, 0)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) {
        init(context, attrs, defStyle)
    }

    private fun init(context: Context, attrs: AttributeSet?, defStyleAttr: Int) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.LoadImageView, defStyleAttr, 0)
        setRetryType(a.getInt(R.styleable.LoadImageView_retryType, 0))
        a.recycle()
        isFocusable = false
    }

    private fun isLoading(): Boolean {
        val request = mRequest
        return request != null && !request.isDisposed
    }

    private fun cancelRequest() {
        val request = mRequest
        if (request != null) {
            request.dispose()
            mRequest = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (!mLoadFromDrawable) {
            if (mFailed) {
                onFailure()
            } else if (!isLoading()) {
                load(mKey, mUrl, mUseNetwork)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        if (!mLoadFromDrawable) {
            cancelRequest()
            // Clear drawable
            clearDrawable()
        }
    }

    private fun getImageDrawable(): Drawable? {
        var drawable = drawable
        if (drawable is TransitionDrawable) {
            if (drawable.numberOfLayers == 2) {
                drawable = drawable.getDrawable(1)
            }
        }
        if (drawable is PreciselyClipDrawable) {
            drawable = drawable.drawable
        }
        return drawable
    }

    private fun clearDrawable() {
        // Set drawable null
        setImageDrawable(null)
    }

    private fun clearRetry() {
        if (mRetryType == RETRY_TYPE_CLICK) {
            setOnClickListener(null)
            isClickable = false
        } else if (mRetryType == RETRY_TYPE_LONG_CLICK) {
            setOnLongClickListener(null)
            isLongClickable = false
        }
    }

    fun setRetryType(@RetryType retryType: Int) {
        if (mRetryType != retryType) {
            val oldRetryType = mRetryType
            mRetryType = retryType

            if (mFailed) {
                if (oldRetryType == RETRY_TYPE_CLICK) {
                    setOnClickListener(null)
                    isClickable = false
                } else if (oldRetryType == RETRY_TYPE_LONG_CLICK) {
                    setOnLongClickListener(null)
                    isLongClickable = false
                }

                if (retryType == RETRY_TYPE_CLICK) {
                    setOnClickListener(this)
                } else if (retryType == RETRY_TYPE_LONG_CLICK) {
                    setOnLongClickListener(this)
                }
            }
        }
    }

    fun setClip(offsetX: Int, offsetY: Int, clipWidth: Int, clipHeight: Int) {
        mOffsetX = offsetX
        mOffsetY = offsetY
        mClipWidth = clipWidth
        mClipHeight = clipHeight
    }

    fun resetClip() {
        mOffsetX = Int.MIN_VALUE
        mOffsetY = Int.MIN_VALUE
        mClipWidth = Int.MIN_VALUE
        mClipHeight = Int.MIN_VALUE
    }

    fun load(key: String?, url: String?) {
        load(key, url, true)
    }

    fun load(key: String?, url: String?, useNetwork: Boolean) {
        load(key, url, null, useNetwork)
    }

    fun load(key: String?, url: String?, store: CoverStore?, useNetwork: Boolean) {
        load(key, url, store, useNetwork, false)
    }

    fun load(key: String?, url: String?, store: CoverStore?, useNetwork: Boolean, hardware: Boolean) {
        if (url == null || key == null) {
            return
        }

        // Same-key short-circuit: rebinding a view whose thumbnail is already
        // in flight must not cancel-and-restart the fetch (cancelled fetches
        // discard their partial bytes). Store loads are exempt — the caller
        // wants the bytes delivered into ITS store instance.
        if (store == null && LoadImageSameKeyPolicy.shouldSkipLoad(
                mKey, key, mFailed, mUseNetwork == useNetwork, isLoading(),
            )
        ) {
            return
        }

        mLoadFromDrawable = false
        mFailed = false
        clearRetry()

        mKey = key
        mUrl = url
        mUseNetwork = useNetwork

        cancelRequest()
        // Callbacks for a memory hit arrive inside load(); clear first so a
        // hit replaces the old image and a miss starts from blank.
        clearDrawable()
        mRequest = CoverLoader.load(this, key, url, useNetwork, hardware, store, this)
    }

    fun unload() {
        cancelRequest()
        mKey = null
        mUrl = null
        clearDrawable()
    }

    override fun onSuccess(drawable: Drawable, fromMemory: Boolean) {
        var target = drawable
        if (Int.MIN_VALUE != mOffsetX) {
            target = PreciselyClipDrawable(target, mOffsetX, mOffsetY, mClipWidth, mClipHeight)
        }

        onPreSetImageDrawable(target, true)
        if (!fromMemory && isShown) {
            val layers = arrayOfNulls<Drawable>(2)
            layers[0] = ColorDrawable(Color.TRANSPARENT)
            layers[1] = target
            val transitionDrawable = TransitionDrawable(layers)
            setImageDrawable(transitionDrawable)
            transitionDrawable.startTransition(300)
        } else {
            setImageDrawable(target)
        }
    }

    override fun onError() {
        onFailure()
    }

    private fun onFailure() {
        mFailed = true
        clearDrawable()
        val drawable = ContextCompat.getDrawable(context, R.drawable.image_failed_new)

        onPreSetImageDrawable(drawable, true)
        setImageDrawable(drawable)
        if (mRetryType == RETRY_TYPE_CLICK) {
            setOnClickListener(this)
        } else if (mRetryType == RETRY_TYPE_LONG_CLICK) {
            setOnLongClickListener(this)
        } else {
            // Can't retry, so release
            mKey = null
            mUrl = null
        }
    }

    override fun start() {
        val drawable = getImageDrawable()
        if (drawable is AnimatedImageDrawable) {
            drawable.start()
        }
    }

    override fun stop() {
        val drawable = getImageDrawable()
        if (drawable is AnimatedImageDrawable) {
            drawable.stop()
        }
    }

    override fun isRunning(): Boolean {
        val drawable = getImageDrawable()
        if (drawable is AnimatedImageDrawable) {
            return drawable.isRunning
        }
        return false
    }

    override fun onClick(v: View) {
        load(mKey, mUrl, true)
    }

    override fun onLongClick(v: View): Boolean {
        load(mKey, mUrl, true)
        return true
    }

    open fun onPreSetImageDrawable(drawable: Drawable?, isTarget: Boolean) {
        // Hook for subclasses: called right before a loaded or failure image is set.
    }

    open fun onPreSetImageResource(resId: Int, isTarget: Boolean) {
        // Hook for subclasses: called right before an image resource is set.
    }

    @IntDef(RETRY_TYPE_NONE, RETRY_TYPE_CLICK, RETRY_TYPE_LONG_CLICK)
    @Retention(AnnotationRetention.SOURCE)
    private annotation class RetryType

    companion object {
        const val RETRY_TYPE_NONE = 0
        const val RETRY_TYPE_CLICK = 1
        const val RETRY_TYPE_LONG_CLICK = 2
    }
}
