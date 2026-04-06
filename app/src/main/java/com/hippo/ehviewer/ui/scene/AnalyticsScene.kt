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

package com.hippo.ehviewer.ui.scene

import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.lib.yorozuya.ViewUtils
import com.hippo.text.LinkMovementMethod2

class AnalyticsScene : SolidScene(), View.OnClickListener {

    private var mReject: View? = null
    private var mAccept: View? = null

    override fun needShowLeftDrawer(): Boolean = false

    override fun onCreateView2(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.scene_analytics, container, false)

        mReject = ViewUtils.`$$`(view, R.id.reject)
        mAccept = ViewUtils.`$$`(view, R.id.accept)
        val text = ViewUtils.`$$`(view, R.id.text) as TextView

        text.text = Html.fromHtml(getString(R.string.analytics_explain), Html.FROM_HTML_MODE_LEGACY)
        text.movementMethod = LinkMovementMethod2.getInstance()

        mReject!!.setOnClickListener(this)
        mAccept!!.setOnClickListener(this)

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mReject = null
        mAccept = null
    }

    override fun onClick(v: View) {
        val context = getEHContext() ?: return

        if (mReject === v) {
            Settings.putEnableAnalytics(false)
        } else if (mAccept === v) {
            Settings.putEnableAnalytics(true)
            // Start Analytics
            Analytics.start(context)
        }
        Settings.putAskAnalytics(false)

        // Start new scene and finish it self
        val activity = activity2
        if (activity != null) {
            startSceneForCheckStep(CHECK_STEP_ANALYTICS, arguments)
        }
        finish()
    }
}
