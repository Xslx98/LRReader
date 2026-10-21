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

package com.lanraragi.framework.widget;


import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.DrawableRes;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.lanraragi.framework.drawable.PreciselyClipDrawable;
import com.lanraragi.reader.R;
import com.lanraragi.reader.cover.CoverLoader;
import com.lanraragi.framework.util.DrawableManager;

import coil3.request.Disposable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class LoadImageViewNew extends FixedAspectImageView implements CoverLoader.Callback,
        View.OnClickListener, View.OnLongClickListener, Animatable {

    public static final int RETRY_TYPE_NONE = 0;
    public static final int RETRY_TYPE_CLICK = 1;
    public static final int RETRY_TYPE_LONG_CLICK = 2;

    /** Fade-in of a loaded image over the placeholder (decision 19: 150 ms). */
    private static final int FADE_IN_MS = 150;

    @Nullable
    private Drawable mPlaceholder;
    @Nullable
    private Disposable mRequest;
    private String mKey;
    private String mUrl;
    private boolean mUseNetwork;
    private int mOffsetX = Integer.MIN_VALUE;
    private int mOffsetY = Integer.MIN_VALUE;
    private int mClipWidth = Integer.MIN_VALUE;
    private int mClipHeight = Integer.MIN_VALUE;
    private int mRetryType;
    public boolean mFailed;
    private boolean mLoadFromDrawable;


    public LoadImageViewNew(Context context) {
        super(context);
        init(context, null, 0);
    }

    public LoadImageViewNew(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs, 0);
    }

    public LoadImageViewNew(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context, attrs, defStyle);
    }

    private void init(Context context, AttributeSet attrs, int defStyleAttr) {
        @SuppressLint("CustomViewStyleable") TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.LoadImageView, defStyleAttr, 0);
        setRetryType(a.getInt(R.styleable.LoadImageView_retryType, 0));
        a.recycle();
        setFocusable(false);
    }

    private boolean isLoading() {
        return mRequest != null && !mRequest.isDisposed();
    }

    private void cancelRequest() {
        if (mRequest != null) {
            mRequest.dispose();
            mRequest = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!mLoadFromDrawable) {
            if (mFailed) {
                onFailure();
            } else if (!isLoading()) {
                load(mKey, mUrl, mUseNetwork);
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (!mLoadFromDrawable) {
            cancelRequest();
            // Clear drawable
            clearDrawable();
        }
    }

    private Drawable getImageDrawable() {
        Drawable drawable = getDrawable();
        if (drawable instanceof TransitionDrawable) {
            TransitionDrawable transitionDrawable = (TransitionDrawable) drawable;
            if (transitionDrawable.getNumberOfLayers() == 2) {
                drawable = transitionDrawable.getDrawable(1);
            }
        }
        if (drawable instanceof PreciselyClipDrawable) {
            drawable = ((PreciselyClipDrawable) drawable).getDrawable();
        }
        return drawable;
    }

    /**
     * Shown instead of an empty view while there is no image (waiting,
     * loading, or detached); the image fades in over it. Null keeps the view
     * blank, as before.
     */
    public void setPlaceholder(@Nullable Drawable placeholder) {
        Drawable current = getDrawable();
        boolean showingPlaceholder = current == null || current == mPlaceholder;
        mPlaceholder = placeholder;
        if (showingPlaceholder) {
            setImageDrawable(placeholder);
        }
    }

    private void clearDrawable() {
        setImageDrawable(mPlaceholder);
    }

    private void clearRetry() {
        if (mRetryType == RETRY_TYPE_CLICK) {
            setOnClickListener(null);
            setClickable(false);
        } else if (mRetryType == RETRY_TYPE_LONG_CLICK) {
            setOnLongClickListener(null);
            setLongClickable(false);
        }
    }

    public void setRetryType(@RetryType int retryType) {
        if (mRetryType != retryType) {
            int oldRetryType = mRetryType;
            mRetryType = retryType;

            if (mFailed) {
                if (oldRetryType == RETRY_TYPE_CLICK) {
                    setOnClickListener(null);
                    setClickable(false);
                } else if (oldRetryType == RETRY_TYPE_LONG_CLICK) {
                    setOnLongClickListener(null);
                    setLongClickable(false);
                }

                if (retryType == RETRY_TYPE_CLICK) {
                    setOnClickListener(this);
                } else if (retryType == RETRY_TYPE_LONG_CLICK) {
                    setOnLongClickListener(this);
                }
            }
        }
    }

    public void setClip(int offsetX, int offsetY, int clipWidth, int clipHeight) {
        mOffsetX = offsetX;
        mOffsetY = offsetY;
        mClipWidth = clipWidth;
        mClipHeight = clipHeight;
    }

    public void resetClip() {
        mOffsetX = Integer.MIN_VALUE;
        mOffsetY = Integer.MIN_VALUE;
        mClipWidth = Integer.MIN_VALUE;
        mClipHeight = Integer.MIN_VALUE;
    }

    public void load() {
        load(mKey, mUrl, true);
    }

    public void load(String key, String url) {
        load(key, url, true);
    }


    public void load(String key, String url, boolean useNetwork) {
        if (url == null || key == null) {
            return;
        }

        // Same-key short-circuit: rebinding a row whose thumbnail is already
        // in flight must not cancel-and-restart the fetch (cancelled fetches
        // discard their partial bytes).
        if (LoadImageSameKeyPolicy.shouldSkipLoad(
                mKey, key, mFailed, mUseNetwork == useNetwork, isLoading())) {
            return;
        }

        mLoadFromDrawable = false;
        mFailed = false;
        clearRetry();

        mKey = key;
        mUrl = url;
        mUseNetwork = useNetwork;

        cancelRequest();
        // Callbacks for a memory hit arrive inside load(); show the
        // placeholder first so a hit replaces it and a miss waits on it.
        clearDrawable();
        mRequest = CoverLoader.load(this, key, url, useNetwork, true, null, this);
    }

    public void load(Drawable drawable) {
        unload();
        mLoadFromDrawable = true;
        onPreSetImageDrawable(drawable, true);
        setImageDrawable(drawable);
    }

    public void load(@DrawableRes int id) {
        unload();
        mLoadFromDrawable = true;
        onPreSetImageResource(id, true);
        setImageResource(id);
    }

    public void unload() {
        cancelRequest();
        mKey = null;
        mUrl = null;
        clearDrawable();
    }

    @Override
    public void onSuccess(@NonNull Drawable drawable, boolean fromMemory) {
        clearDrawable();

        if (Integer.MIN_VALUE != mOffsetX) {
            drawable = new PreciselyClipDrawable(drawable, mOffsetX, mOffsetY, mClipWidth, mClipHeight);
        }

        onPreSetImageDrawable(drawable, true);
        if (!fromMemory && isShown()) {
            Drawable[] layers = new Drawable[2];
            layers[0] = mPlaceholder != null ? mPlaceholder : new ColorDrawable(Color.TRANSPARENT);
            layers[1] = drawable;
            TransitionDrawable transitionDrawable = new TransitionDrawable(layers);
            transitionDrawable.setCrossFadeEnabled(true);
            setImageDrawable(transitionDrawable);
            transitionDrawable.startTransition(FADE_IN_MS);
        } else {
            setImageDrawable(drawable);
        }
    }

    @Override
    public void onError() {
        onFailure();
    }

    private void onFailure() {
        mFailed = true;
        clearDrawable();
        Drawable drawable;
        drawable = DrawableManager.getVectorDrawable(getContext(), R.drawable.image_failed_new);

        onPreSetImageDrawable(drawable, true);
        setImageDrawable(drawable);
        if (mRetryType == RETRY_TYPE_CLICK && mFailed) {
            setOnClickListener(this);
        } else if (mRetryType == RETRY_TYPE_LONG_CLICK) {
            setOnLongClickListener(this);
        } else {
            // Can't retry, so release
            mKey = null;
            mUrl = null;
        }
    }

    @Override
    public void start() {
        Drawable drawable = getImageDrawable();
        if (drawable instanceof AnimatedImageDrawable animatedImageDrawable) {
            animatedImageDrawable.start();
        }
    }

    @Override
    public void stop() {
        Drawable drawable = getImageDrawable();
        if (drawable instanceof AnimatedImageDrawable animatedImageDrawable) {
            animatedImageDrawable.stop();
        }

    }

    @Override
    public boolean isRunning() {
        Drawable drawable = getImageDrawable();
        if (drawable instanceof AnimatedImageDrawable animatedImageDrawable) {
            return animatedImageDrawable.isRunning();
        }
        return false;

    }

    @Override
    public void onClick(@NonNull View v) {
        load(mKey, mUrl, true);
    }

    @Override
    public boolean onLongClick(@NonNull View v) {
        load(mKey, mUrl, true);
        return true;
    }

    public void onPreSetImageDrawable(Drawable drawable, boolean isTarget) {
    }

    public void onPreSetImageResource(int resId, boolean isTarget) {
    }

    @IntDef({RETRY_TYPE_NONE, RETRY_TYPE_CLICK, RETRY_TYPE_LONG_CLICK})
    @Retention(RetentionPolicy.SOURCE)
    private @interface RetryType {
    }
}
