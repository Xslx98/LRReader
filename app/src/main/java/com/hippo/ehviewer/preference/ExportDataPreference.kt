/*
 * Copyright 2019 Hippo Seven
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
import android.widget.Toast
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.GetText
import com.hippo.ehviewer.R
import com.hippo.util.ReadableTime
import java.io.File

class ExportDataPreference : TaskPreference {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun onCreateTask(): Task = ExportDataTask(context)

    private class ExportDataTask(context: Context) : Task(context) {

        override fun doWork(): Any? {
            val dir = AppConfig.getExternalDataDir() ?: return null
            val file = File(dir, ReadableTime.getFilenamableTime(System.currentTimeMillis()) + ".db")
            return if (EhDB.exportDB(getApplication(), file)) file else null
        }

        override fun onPostExecute(result: Any?) {
            Toast.makeText(
                getApplication(),
                if (result is File) {
                    GetText.getString(R.string.settings_advanced_export_data_to, result.path)
                } else {
                    GetText.getString(R.string.settings_advanced_export_data_failed)
                },
                Toast.LENGTH_SHORT
            ).show()
            super.onPostExecute(result)
        }
    }
}
