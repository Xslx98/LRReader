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

import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.lrr.LRRSecureStorageUnavailableException
import com.hippo.ehviewer.settings.SecuritySettings
import com.hippo.lib.yorozuya.ViewUtils
import com.hippo.widget.lockpattern.LockPatternView

class SetSecurityActivity : ToolbarActivity(), View.OnClickListener {

    private var mPatternView: LockPatternView? = null
    private var mCancel: View? = null
    private var mSet: View? = null
    private var mFingerprint: CheckBox? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_set_security)
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24)

        mPatternView = ViewUtils.`$$`(this, R.id.pattern_view) as LockPatternView
        mCancel = ViewUtils.`$$`(this, R.id.cancel)
        mSet = ViewUtils.`$$`(this, R.id.set)
        mFingerprint = ViewUtils.`$$`(this, R.id.fingerprint_checkbox) as CheckBox

        // Pattern is stored as a hash and cannot be recovered for display.
        // The view starts empty regardless of whether a pattern was previously set.

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val fingerprintManager = getSystemService(FingerprintManager::class.java)
            if (fingerprintManager != null && hasEnrolledFingerprints(fingerprintManager)) {
                mFingerprint!!.visibility = View.VISIBLE
                mFingerprint!!.isChecked = SecuritySettings.getEnableFingerprint()
            }
        }

        mCancel!!.setOnClickListener(this)
        mSet!!.setOnClickListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        mPatternView = null
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
        if (v === mCancel) {
            finish()
        } else if (v === mSet) {
            if (mPatternView != null && mFingerprint != null) {
                val security = if (mPatternView!!.cellSize <= 1) {
                    ""
                } else {
                    mPatternView!!.patternString
                }
                try {
                    SecuritySettings.setPattern(security)
                    SecuritySettings.putEnableFingerprint(
                        mFingerprint!!.visibility == View.VISIBLE &&
                            mFingerprint!!.isChecked && security.isNotEmpty()
                    )
                } catch (e: LRRSecureStorageUnavailableException) {
                    // KeyStore unavailable — show error dialog and keep activity open.
                    showStorageErrorDialog()
                    return
                }
            }
            finish()
        }
    }

    private fun showStorageErrorDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.lrr_keystore_failed_title)
            .setMessage(R.string.lrr_secure_storage_write_failed)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        @RequiresApi(api = Build.VERSION_CODES.M)
        @JvmStatic
        fun hasEnrolledFingerprints(fingerprintManager: FingerprintManager): Boolean {
            return try {
                @Suppress("DEPRECATION")
                fingerprintManager.isHardwareDetected && fingerprintManager.hasEnrolledFingerprints()
            } catch (e: Exception) {
                false
            }
        }
    }
}
