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

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.hippo.ehviewer.R
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRSecureStorageUnavailableException
import com.hippo.ehviewer.settings.SecuritySettings
import com.hippo.lib.yorozuya.ViewUtils
import com.hippo.widget.lockpattern.LockPatternView

class SetSecurityActivity : ToolbarActivity(), View.OnClickListener {

    companion object {
        private const val TAG = "SetSecurityActivity"

        @JvmStatic
        fun canAuthenticateBiometric(context: Context): Boolean {
            val biometricManager = BiometricManager.from(context)
            return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }
    }

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

        if (canAuthenticateBiometric(this)) {
            mFingerprint!!.visibility = View.VISIBLE
            mFingerprint!!.isChecked = SecuritySettings.getEnableFingerprint()
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
                savePattern(security)
            }
        }
    }

    private fun savePattern(security: String) {
        if (security.isEmpty()) {
            // Clearing the pattern — no biometric needed
            try {
                SecuritySettings.setPattern("")
                SecuritySettings.putEnableFingerprint(false)
            } catch (e: LRRSecureStorageUnavailableException) {
                showStorageErrorDialog()
                return
            }
            finish()
            return
        }

        // Check if strong biometrics are available for KeyStore binding
        if (canUseKeystoreBinding()) {
            setPatternWithKeystoreBinding(security)
        } else {
            // No biometrics: PBKDF2-only flow
            setPatternPbkdf2Only(security)
        }
    }

    private fun canUseKeystoreBinding(): Boolean {
        val biometricManager = BiometricManager.from(this)
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun setPatternPbkdf2Only(security: String) {
        try {
            SecuritySettings.setPattern(security)
            SecuritySettings.putEnableFingerprint(
                mFingerprint!!.visibility == View.VISIBLE &&
                    mFingerprint!!.isChecked && security.isNotEmpty()
            )
        } catch (e: LRRSecureStorageUnavailableException) {
            showStorageErrorDialog()
            return
        }
        finish()
    }

    private fun setPatternWithKeystoreBinding(security: String) {
        // Generate the KeyStore key first
        try {
            LRRAuthManager.generatePatternKeystoreKey()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate KeyStore key, falling back to PBKDF2", e)
            setPatternPbkdf2Only(security)
            return
        }

        // Get an encrypt cipher to present to BiometricPrompt
        val cipher: javax.crypto.Cipher
        try {
            cipher = LRRAuthManager.getEncryptCipher()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get encrypt cipher, falling back to PBKDF2", e)
            LRRAuthManager.deletePatternKeystoreKey()
            setPatternPbkdf2Only(security)
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authCipher = result.cryptoObject?.cipher
                    if (authCipher == null) {
                        Log.e(TAG, "BiometricPrompt returned null cipher")
                        setPatternPbkdf2Only(security)
                        return
                    }
                    try {
                        LRRAuthManager.setPatternWithCipher(security, authCipher)
                        SecuritySettings.putEnableFingerprint(
                            mFingerprint!!.visibility == View.VISIBLE &&
                                mFingerprint!!.isChecked && security.isNotEmpty()
                        )
                    } catch (e: LRRSecureStorageUnavailableException) {
                        showStorageErrorDialog()
                        return
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to set pattern with cipher, falling back", e)
                        LRRAuthManager.deletePatternKeystoreKey()
                        setPatternPbkdf2Only(security)
                        return
                    }
                    finish()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED) {
                        // User cancelled — fall back to PBKDF2-only silently
                        setPatternPbkdf2Only(security)
                    } else {
                        Toast.makeText(this@SetSecurityActivity, errString, Toast.LENGTH_SHORT).show()
                        LRRAuthManager.deletePatternKeystoreKey()
                        setPatternPbkdf2Only(security)
                    }
                }

                override fun onAuthenticationFailed() {
                    // Biometric didn't match — user can retry via the prompt
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.settings_privacy_pattern_protection_title))
            .setDescription(getString(R.string.biometric_prompt_pattern_set))
            .setNegativeButtonText(getString(android.R.string.cancel))
            .build()

        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    private fun showStorageErrorDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.lrr_keystore_failed_title)
            .setMessage(R.string.lrr_secure_storage_write_failed)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
