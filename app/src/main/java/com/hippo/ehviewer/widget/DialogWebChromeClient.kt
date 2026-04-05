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

package com.hippo.ehviewer.widget

import android.content.Context
import android.view.LayoutInflater
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.hippo.ehviewer.R

class DialogWebChromeClient(private val context: Context) : WebChromeClient() {

    override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        AlertDialog.Builder(view.context)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }

    override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        AlertDialog.Builder(view.context)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }

    override fun onJsPrompt(
        view: WebView,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult
    ): Boolean {
        val inflater = LayoutInflater.from(context)
        val promptView = inflater.inflate(R.layout.dialog_js_prompt, null, false)
        val messageView = promptView.findViewById<TextView>(R.id.message)
        messageView.text = message
        val valueView = promptView.findViewById<EditText>(R.id.value)
        valueView.setText(defaultValue)

        AlertDialog.Builder(context)
            .setView(promptView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                result.confirm(valueView.text.toString())
            }
            .setOnCancelListener { result.cancel() }
            .show()

        return true
    }
}
