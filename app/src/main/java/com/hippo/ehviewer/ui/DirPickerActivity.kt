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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.hippo.android.resource.AttrResources
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.R
import com.hippo.lib.yorozuya.FileUtils
import com.hippo.ripple.Ripple
import com.hippo.widget.DirExplorer
import java.io.File

class DirPickerActivity : ToolbarActivity(),
    View.OnClickListener, DirExplorer.OnChangeDirListener {

    private var mPath: TextView? = null
    private var mDirExplorer: DirExplorer? = null
    private var mDefault: View? = null
    private var mOk: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dir_picker)
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24)

        mPath = findViewById(R.id.path)
        mDirExplorer = findViewById(R.id.dir_explorer)
        mDefault = findViewById(R.id.preset)
        mOk = findViewById(R.id.ok)

        val file = if (savedInstanceState == null) onInit() else onRestore(savedInstanceState)

        val dirExplorer = mDirExplorer ?: return
        dirExplorer.setCurrentFile(file)
        dirExplorer.setOnChangeDirListener(this)

        val okButton = mOk ?: return
        Ripple.addRipple(okButton, !AttrResources.getAttrBoolean(this, androidx.appcompat.R.attr.isLightTheme))

        mDefault?.setOnClickListener(this)
        okButton.setOnClickListener(this)

        mPath?.text = dirExplorer.currentFile.path
    }

    private fun onInit(): File? {
        val intent = intent
        if (intent != null) {
            @Suppress("DEPRECATION")
            val fileUri = intent.getParcelableExtra<Uri>(KEY_FILE_URI)
            if (fileUri != null) {
                return File(fileUri.path ?: return null)
            }
        }
        return null
    }

    private fun onRestore(savedInstanceState: Bundle): File? {
        val filePath = savedInstanceState.getString(KEY_FILE_PATH)
        return if (filePath != null) File(filePath) else null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mDirExplorer?.let {
            outState.putString(KEY_FILE_PATH, it.currentFile.path)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mPath = null
        mDirExplorer = null
        mOk = null
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onClick(v: View) {
        if (mDefault === v) {
            val externalFilesDirs = ContextCompat.getExternalFilesDirs(this, null)
            val dirs = arrayOfNulls<File>(externalFilesDirs.size + 1)
            dirs[0] = AppConfig.getDefaultDownloadDir()
            for (i in externalFilesDirs.indices) {
                dirs[i + 1] = File(externalFilesDirs[i], "download")
            }

            val items = arrayOfNulls<CharSequence>(dirs.size)
            items[0] = getString(R.string.default_directory)
            for (i in 1 until items.size) {
                items[i] = getString(R.string.application_file_directory, i)
            }

            AlertDialog.Builder(this).setItems(items) { _, which ->
                val dir = dirs[which]
                if (!FileUtils.ensureDirectory(dir)) {
                    Toast.makeText(this@DirPickerActivity, R.string.directory_not_writable, Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                mDirExplorer?.setCurrentFile(dir)
            }.show()
        } else if (mOk === v) {
            val dirExplorer = mDirExplorer ?: return
            val file = dirExplorer.currentFile
            if (!file.canWrite()) {
                Toast.makeText(this, R.string.directory_not_writable, Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent()
                intent.data = Uri.fromFile(file)
                setResult(RESULT_OK, intent)
                finish()
            }
        }
    }

    override fun onChangeDir(dir: File) {
        mPath?.text = dir.path
    }

    companion object {
        const val KEY_FILE_URI = "file_uri"
        const val KEY_FILE_PATH = "file_path"
    }
}
