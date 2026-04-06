/*
 * Copyright 2016 Hippo Seven
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

package com.hippo.ehviewer.ui

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.Toolbar
import com.hippo.ehviewer.R
import com.hippo.ehviewer.settings.AppearanceSettings

abstract class ToolbarActivity : EhActivity() {

    override fun getThemeResId(theme: Int): Int {
        return when (theme) {
            AppearanceSettings.THEME_DARK -> R.style.AppTheme_Toolbar_Dark
            AppearanceSettings.THEME_BLACK -> R.style.AppTheme_Toolbar_Black
            else -> R.style.AppTheme_Toolbar
        }
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(R.layout.activity_toolbar)
        layoutInflater.inflate(layoutResID, findViewById<ViewGroup>(R.id.content_panel), true)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
    }

    override fun setContentView(view: View?) {
        super.setContentView(R.layout.activity_toolbar)
        (findViewById<ViewGroup>(R.id.content_panel)).addView(view)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        super.setContentView(R.layout.activity_toolbar)
        (findViewById<ViewGroup>(R.id.content_panel)).addView(view, params)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
    }

    fun setNavigationIcon(@DrawableRes resId: Int) {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar?.setNavigationIcon(resId)
    }

    fun setNavigationIcon(icon: Drawable?) {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar?.navigationIcon = icon
    }
}
