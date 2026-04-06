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

package com.hippo.ehviewer.preference

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.app.AlertDialog
import com.hippo.ehviewer.R
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.widget.CategoryTable
import com.hippo.lib.yorozuya.ViewUtils
import com.hippo.preference.DialogPreference

class DefaultCategoryPreference : DialogPreference {

    constructor(context: Context) : super(context) { init() }
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) { init() }
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { init() }

    private var mCategoryTable: CategoryTable? = null

    private fun init() {
        setDialogLayoutResource(R.layout.preference_dialog_default_categories)
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)
        builder.setPositiveButton(android.R.string.ok, this)
    }

    override fun onDialogCreated(dialog: AlertDialog) {
        super.onDialogCreated(dialog)
        mCategoryTable = ViewUtils.`$$`(dialog, R.id.category_table) as CategoryTable
        mCategoryTable!!.setCategory(AppearanceSettings.getDefaultCategories())
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        super.onDialogClosed(positiveResult)
        if (mCategoryTable != null && positiveResult) {
            AppearanceSettings.putDefaultCategories(mCategoryTable!!.getCategory())
        }
        mCategoryTable = null
    }
}
