/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.hippo.ehviewer.ui.widget

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.core.content.withStyledAttributes
import com.hippo.ehviewer.R
import com.hippo.widget.FixedAspectImageView

/**
 * ImageView that locks the view's own width:height ratio (via the
 * inherited `app:aspect` attribute) and picks between `CENTER_CROP`
 * and `FIT_CENTER` based on the bound drawable's intrinsic ratio.
 *
 * Built for the detail-page page-thumbnail grid: LANraragi archives
 * mix portrait scans, square CG packs and landscape illustrations
 * under one server, so cells need a uniform shape regardless of the
 * source aspect. The smart-crop rule (`CENTER_CROP` when the source
 * ratio is "close enough" to the tile shape, `FIT_CENTER` with
 * letterbox when it's far off) keeps the grid rhythm without slicing
 * off heads or feet of long horizontal scans.
 *
 * Reuses [R.styleable.FixedThumb] for `minAspect` / `maxAspect` —
 * same semantics as the legacy EhViewer FixedThumb widget, just
 * without the `LoadImageView` / Conaco coupling that doesn't fit our
 * direct-Bitmap pipeline.
 *
 * Convention:
 *  - `app:aspect = W / H` — view aspect. `0.667` ≈ 2:3 portrait tile.
 *  - `app:minAspect`, `app:maxAspect` — bounds on the drawable's
 *    intrinsic `W / H`. Inside (exclusive) → `CENTER_CROP`. Outside →
 *    `FIT_CENTER`. Defaults are `0` / `Float.MAX_VALUE` so an
 *    unconfigured view always `CENTER_CROP`s, matching the stock
 *    ImageView behaviour.
 */
class AspectClampedImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FixedAspectImageView(context, attrs, defStyle) {

    private var minAspect: Float = 0f
    private var maxAspect: Float = Float.MAX_VALUE

    init {
        context.withStyledAttributes(attrs, R.styleable.FixedThumb, defStyle, 0) {
            minAspect = getFloat(R.styleable.FixedThumb_minAspect, 0f)
            maxAspect = getFloat(R.styleable.FixedThumb_maxAspect, Float.MAX_VALUE)
        }
    }

    override fun setImageDrawable(drawable: Drawable?) {
        // Pick the scale type before delegating to super so the
        // resulting layout pass already uses the right matrix —
        // avoids a redundant requestLayout when the adapter chains
        // setImageBitmap → setImageDrawable on every bind.
        scaleType = chooseScaleType(drawable)
        super.setImageDrawable(drawable)
    }

    private fun chooseScaleType(d: Drawable?): ScaleType {
        if (d == null) return ScaleType.FIT_CENTER
        val iw = d.intrinsicWidth
        val ih = d.intrinsicHeight
        if (iw <= 0 || ih <= 0) return ScaleType.FIT_CENTER
        val ratio = iw.toFloat() / ih.toFloat()
        return if (ratio > minAspect && ratio < maxAspect) {
            ScaleType.CENTER_CROP
        } else {
            ScaleType.FIT_CENTER
        }
    }
}
