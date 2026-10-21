@file:JvmName("ThemeAttrs")

package com.lanraragi.reader.util

import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.withStyledAttributes
import com.google.android.material.color.MaterialColors

/*
 * Theme attribute lookups (replace the seven332 android-resource
 * `AttrResources` helper).
 */

/**
 * The colour [attr] resolves to in this context's theme; a colour state
 * list gives its default colour. Throws if the theme does not define it.
 */
@ColorInt
fun Context.attrColor(@AttrRes attr: Int): Int = MaterialColors.getColor(this, attr, "attrColor")

/** Whether this context's theme is a light one (AppCompat `isLightTheme`). */
fun Context.isLightTheme(): Boolean {
    var light = true
    withStyledAttributes(attrs = intArrayOf(androidx.appcompat.R.attr.isLightTheme)) {
        light = getBoolean(0, true)
    }
    return light
}

/**
 * The drawable [attr] resolves to in this context's theme: a plain colour
 * becomes a [ColorDrawable]. Throws if the theme does not define it.
 */
fun Context.attrDrawable(@AttrRes attr: Int): Drawable? {
    val value = TypedValue()
    if (!theme.resolveAttribute(attr, value, true)) {
        throw Resources.NotFoundException("Can't resolve attribute ID #0x" + Integer.toHexString(attr))
    }
    return if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
        ColorDrawable(value.data)
    } else {
        AppCompatResources.getDrawable(this, value.resourceId)
    }
}
