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

package com.lanraragi.reader.ui

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.text.Html
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import android.widget.ScrollView
import android.widget.TextView
import com.lanraragi.reader.R

class LicenseActivity : ToolbarActivity() {

    private var mWebView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val html = readAsset()
        setContentView(createWebView(html) ?: createTextFallback(html))

        setNavigationIcon(R.drawable.v_arrow_left_dark_x24)
    }

    private fun readAsset(): String = try {
        assets.open(ASSET).bufferedReader().use { it.readText() }
    } catch (e: java.io.IOException) {
        Log.e(TAG, "Read $ASSET", e)
        ""
    }

    /**
     * WebView construction throws while the WebView provider is updating or
     * disabled (MissingWebViewPackageException / AndroidRuntimeException);
     * fall back to plain text instead of crashing (audit 2026-10-04 C34 /
     * STAB-21).
     */
    private fun createWebView(html: String): View? = try {
        WebView(this).also {
            // Let the themed window background show through the page.
            it.setBackgroundColor(Color.TRANSPARENT)
            it.loadDataWithBaseURL(ASSET_BASE_URL, themed(html, isNightMode()), "text/html", "utf-8", null)
            mWebView = it
        }
    } catch (e: RuntimeException) {
        Log.e(TAG, "WebView unavailable; showing licenses as text", e)
        null
    }

    private fun createTextFallback(html: String): View {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val textView = TextView(this).apply {
            setPadding(padding, padding, padding, padding)
            setTextIsSelectable(true)
            text = Html.fromHtml(bodyOf(html), Html.FROM_HTML_MODE_COMPACT)
        }
        return ScrollView(this).apply { addView(textView) }
    }

    private fun isNightMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    override fun onDestroy() {
        super.onDestroy()
        mWebView?.destroy()
        mWebView = null
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

    companion object {
        private const val TAG = "LicenseActivity"
        internal const val ASSET = "open_source_licenses.html"
        private const val ASSET_BASE_URL = "file:///android_asset/"
        private val HTML_TAG = Regex("<html\\b")

        /**
         * The app theme is always a Light parent (night colors come from
         * values-night), so the WebView never matches
         * `prefers-color-scheme: dark`; the page keys its dark palette off a
         * `dark` class on the root element instead.
         */
        internal fun themed(html: String, night: Boolean): String =
            if (night) html.replaceFirst(HTML_TAG, "<html class=\"dark\"") else html

        /**
         * [Html.fromHtml] prints the text of `<style>` and `<title>`, so the
         * text fallback renders only the body.
         */
        internal fun bodyOf(html: String): String {
            val start = html.indexOf("<body")
            return if (start < 0) html else html.substring(start)
        }
    }
}
