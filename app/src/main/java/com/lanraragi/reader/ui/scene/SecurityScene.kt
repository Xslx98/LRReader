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

package com.lanraragi.reader.ui.scene

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.lanraragi.reader.R
import com.lanraragi.reader.settings.AppLockGate
import com.lanraragi.reader.settings.SecuritySettings
import com.lanraragi.reader.ui.scene.SecurityViewModel.SecurityUiEvent
import com.lanraragi.reader.util.collectFlow
import com.lanraragi.framework.widget.lockpattern.LockPatternUtils
import com.lanraragi.framework.widget.lockpattern.LockPatternView
import com.lanraragi.framework.lib.yorozuya.ViewUtils
import com.lanraragi.reader.LRReaderApplication
import com.lanraragi.reader.client.api.LRRAuthManager

class SecurityScene : SolidScene(),
    LockPatternView.OnPatternListener {

    companion object {
        private const val TAG = "SecurityScene"
        private const val ERROR_TIMEOUT_MILLIS = 1200L
        private const val SUCCESS_DELAY_MILLIS = 100L
        private const val LOCKOUT_UPDATE_INTERVAL_MS = 1000L


        /**
         * If true, this scene was pushed by the foreground re-lock flow
         * (AppLockGate). On successful unlock just pop back to the previous
         * scene instead of running the cold-start `startSceneForCheckStep`
         * logic, which would otherwise reset navigation to GalleryListScene.
         */
        const val KEY_RELOCK_MODE = "relock_mode"
    }

    private val isRelockMode: Boolean
        get() = arguments?.getBoolean(KEY_RELOCK_MODE, false) == true

    private fun dismissAfterUnlock() {
        if (ehContext == null || !isAdded) return
        if (isRelockMode) {
            // Capture context + resume intent BEFORE finishing — finish()
            // detaches this fragment so ehContext may turn null right after.
            val ctx = ehContext
            val resumeIntent = AppLockGate.consumeResumeIntent()
            finish()
            if (ctx != null && resumeIntent != null) {
                ctx.startActivity(resumeIntent)
            }
        } else {
            startSceneForCheckStep(CHECK_STEP_SECURITY, arguments)
            finish()
        }
    }

    private lateinit var viewModel: SecurityViewModel

    private var mPatternView: LockPatternView? = null
    private lateinit var mFingerprintIcon: ImageView
    private var mLockoutText: TextView? = null

    private var mBiometricPrompt: BiometricPrompt? = null

    private val mHandler = Handler(Looper.getMainLooper())
    private val mLockoutUpdateRunnable = object : Runnable {
        override fun run() {
            viewModel.refreshLockout()
            updateLockoutUi()
            if (viewModel.lockoutState.value.isLockedOut) {
                mHandler.postDelayed(this, LOCKOUT_UPDATE_INTERVAL_MS)
            }
        }
    }

    override fun needShowLeftDrawer(): Boolean = false

    /**
     * Back never dismisses the lock screen (the default finish() revealed
     * the scene underneath): it sends the whole app to the background, like
     * the system lock screen. The prompt is still there on return.
     */
    override fun onBackPressed() {
        activity?.moveTaskToBack(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[SecurityViewModel::class.java]
    }

    override fun onDestroy() {
        super.onDestroy()

        // If a re-lock prompt is being torn down without a successful unlock
        // (user pressed back, scene replaced, etc.), drop the stashed reader
        // resume intent so a later unrelated unlock doesn't revive a stale
        // page. Successful-unlock paths consume the intent in
        // dismissAfterUnlock before calling finish(), so this is a no-op
        // in the happy path.
        //
        // Skip on a configuration change (rotation): the scene is being
        // recreated, not dismissed, so consuming here would discard the
        // resume intent and strand the user away from the reader after they
        // unlock the re-created prompt.
        if (isRelockMode && activity?.isChangingConfigurations != true) {
            AppLockGate.consumeResumeIntent()
        }
    }

    override fun onResume() {
        super.onResume()

        if (secureStorageAvailable && isFingerprintAuthAvailable()) {
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.settings_privacy_pattern_protection_title))
                .setNegativeButtonText(getString(android.R.string.cancel))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
            mBiometricPrompt?.authenticate(promptInfo)
        }

        // Update lockout UI on resume
        viewModel.refreshLockout()
        updateLockoutUi()
        if (viewModel.lockoutState.value.isLockedOut) {
            mHandler.postDelayed(mLockoutUpdateRunnable, LOCKOUT_UPDATE_INTERVAL_MS)
        }
    }

    override fun onPause() {
        super.onPause()

        mBiometricPrompt?.cancelAuthentication()
        mHandler.removeCallbacks(mLockoutUpdateRunnable)
    }

    override fun onCreateView2(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.scene_security, container, false)

        val patternView = ViewUtils.`$$`(view, R.id.pattern_view) as LockPatternView
        mPatternView = patternView
        patternView.setOnPatternListener(this)

        mFingerprintIcon = ViewUtils.`$$`(view, R.id.fingerprint_icon) as ImageView
        if (SecuritySettings.getEnableFingerprint() && isFingerprintAuthAvailable()) {
            mFingerprintIcon.visibility = View.VISIBLE
            mFingerprintIcon.setImageResource(R.drawable.ic_fp_40px)
        }

        // Set up BiometricPrompt for fingerprint unlock
        val executor = ContextCompat.getMainExecutor(requireContext())
        mBiometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    fingerprintError(true)
                }

                override fun onAuthenticationFailed() {
                    fingerprintError(false)
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    mFingerprintIcon.setImageResource(R.drawable.fingerprint_success)
                    mFingerprintIcon.postDelayed({
                        dismissAfterUnlock()
                    }, SUCCESS_DELAY_MILLIS)
                }
            })

        // Find or create a lockout message view. The layout may not have this view,
        // so we look for it by id and only use it if present.
        mLockoutText = view.findViewById(R.id.lockout_text)

        // Observe ViewModel events
        collectFlow(viewLifecycleOwner, viewModel.uiEvent) { event ->
            handleUiEvent(event)
        }

        // Fail closed: with the secure store unreadable the pattern cannot be
        // checked, but the app stays locked. Offer a retry or a reset.
        secureStorageAvailable = LRRAuthManager.isSecureStorageAvailable()
        if (!secureStorageAvailable) {
            patternView.isEnabled = false
            showStorageUnavailableDialog()
        }

        return view
    }

    private var secureStorageAvailable = true

    private fun showStorageUnavailableDialog() {
        val ctx = ehContext ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.lrr_keystore_failed_title)
            .setMessage(R.string.security_storage_unavailable_message)
            .setCancelable(false)
            .setPositiveButton(R.string.security_storage_retry) { _, _ ->
                // A fresh process re-opens the keystore; a transient failure
                // (system update, biometric re-enrolment) often clears.
                (ctx.applicationContext as LRReaderApplication).restart()
            }
            .setNegativeButton(R.string.security_reset_app_lock) { _, _ -> confirmResetAppLock() }
            .show()
    }

    private fun showPatternUnverifiableDialog() {
        mPatternView?.isEnabled = false
        val ctx = ehContext ?: return
        AlertDialog.Builder(ctx)
            .setMessage(R.string.security_pattern_unverifiable_message)
            .setCancelable(false)
            .setPositiveButton(R.string.security_reset_app_lock) { _, _ ->
                confirmResetAppLock(onCancel = ::showPatternUnverifiableDialog)
            }
            .show()
    }

    private fun confirmResetAppLock(onCancel: () -> Unit = ::showStorageUnavailableDialog) {
        val ctx = ehContext ?: return
        AlertDialog.Builder(ctx)
            .setMessage(R.string.security_reset_app_lock_confirm)
            .setCancelable(false)
            .setPositiveButton(R.string.security_reset_app_lock) { _, _ ->
                LRRAuthManager.resetAppLockAndCredentials(ctx)
                AppLockGate.reset()
                (ctx.applicationContext as LRReaderApplication).restart()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        mPatternView = null
        mBiometricPrompt = null
        mLockoutText = null
        mHandler.removeCallbacks(mLockoutUpdateRunnable)
    }

    // -------------------------------------------------------------------------
    // Pattern listener
    // -------------------------------------------------------------------------

    override fun onPatternStart() {}

    override fun onPatternCleared() {}

    override fun onPatternCellAdded(pattern: List<LockPatternView.Cell>) {}

    override fun onPatternDetected(pattern: List<LockPatternView.Cell>) {
        val patternView = mPatternView ?: return

        // Check lockout before attempting verification
        if (viewModel.lockoutState.value.isLockedOut) {
            patternView.setDisplayMode(LockPatternView.DisplayMode.Wrong)
            viewModel.refreshLockout()
            updateLockoutUi()
            return
        }

        val enteredPattern = LockPatternUtils.patternToString(pattern)
        viewModel.onPatternDetected(enteredPattern)
    }

    // -------------------------------------------------------------------------
    // ViewModel event handling
    // -------------------------------------------------------------------------

    private fun handleUiEvent(event: SecurityUiEvent) {
        when (event) {
            is SecurityUiEvent.PatternVerified -> {
                dismissAfterUnlock()
            }

            is SecurityUiEvent.PatternFailed -> {
                val patternView = mPatternView ?: return
                patternView.setDisplayMode(LockPatternView.DisplayMode.Wrong)
                updateLockoutUi()
                if (viewModel.lockoutState.value.isLockedOut) {
                    mHandler.postDelayed(mLockoutUpdateRunnable, LOCKOUT_UPDATE_INTERVAL_MS)
                }
            }

            is SecurityUiEvent.NeedBiometricVerification -> {
                verifyWithBiometric(event.pattern)
            }

            is SecurityUiEvent.VerifiedAfterFingerprintChange -> {
                ehContext?.let {
                    Toast.makeText(it, R.string.security_fingerprint_changed, Toast.LENGTH_LONG).show()
                }
                dismissAfterUnlock()
            }

            is SecurityUiEvent.PatternUnverifiable -> showPatternUnverifiableDialog()
        }
    }

    // -------------------------------------------------------------------------
    // Biometric verification (requires Fragment for BiometricPrompt)
    // -------------------------------------------------------------------------

    private fun verifyWithBiometric(enteredPattern: String) {
        val activity = activity2 ?: return

        val cipher = viewModel.getDecryptCipher()
        if (cipher == null) {
            // KeyStore key invalidated — fall back to PBKDF2
            viewModel.onBiometricCipherUnavailable(enteredPattern)
            return
        }

        val fragmentActivity = activity as? FragmentActivity ?: return
        val executor = ContextCompat.getMainExecutor(fragmentActivity)

        val prompt = BiometricPrompt(fragmentActivity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    viewModel.onBiometricVerificationResult(
                        enteredPattern,
                        result.cryptoObject?.cipher
                    )
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // User cancelled or hardware error — don't count as pattern failure
                    mPatternView?.clearPattern()
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_CANCELED) {
                        val ctx = ehContext ?: return
                        Toast.makeText(ctx, errString, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onAuthenticationFailed() {
                    // Biometric didn't match — don't count as pattern failure, user can retry
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.settings_privacy_pattern_protection_title))
            .setDescription(getString(R.string.biometric_prompt_pattern_verify))
            .setNegativeButtonText(getString(android.R.string.cancel))
            .build()

        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    // -------------------------------------------------------------------------
    // Lockout UI
    // -------------------------------------------------------------------------

    private fun updateLockoutUi() {
        val state = viewModel.lockoutState.value
        if (state.isLockedOut) {
            mPatternView?.isEnabled = false
            mLockoutText?.visibility = View.VISIBLE
            mLockoutText?.text = resources.getQuantityString(
                R.plurals.pattern_lockout_message, state.remainingSeconds, state.remainingSeconds
            )
        } else {
            mPatternView?.isEnabled = true
            mLockoutText?.visibility = View.GONE
        }
    }

    // -------------------------------------------------------------------------
    // Fingerprint helpers
    // -------------------------------------------------------------------------

    private fun isFingerprintAuthAvailable(): Boolean {
        if (!SecuritySettings.getEnableFingerprint()) return false
        val context = ehContext ?: return false
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    private val mResetFingerprintRunnable = Runnable {
        mFingerprintIcon.setImageResource(R.drawable.ic_fp_40px)
    }

    private fun fingerprintError(unrecoverable: Boolean) {
        // Do not decrease retry times here since Android system will handle it :)
        mFingerprintIcon.setImageResource(R.drawable.fingerprint_error)
        mFingerprintIcon.removeCallbacks(mResetFingerprintRunnable)
        if (unrecoverable) {
            mFingerprintIcon.postDelayed({
                mFingerprintIcon.visibility = View.INVISIBLE
            }, ERROR_TIMEOUT_MILLIS)
        } else {
            mFingerprintIcon.postDelayed(mResetFingerprintRunnable, ERROR_TIMEOUT_MILLIS)
        }
    }
}
