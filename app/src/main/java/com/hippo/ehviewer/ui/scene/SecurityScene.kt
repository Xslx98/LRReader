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

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
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
import com.hippo.ehviewer.R
import com.hippo.ehviewer.settings.SecuritySettings
import com.hippo.ehviewer.ui.scene.SecurityViewModel.SecurityUiEvent
import com.hippo.ehviewer.util.collectFlow
import com.hippo.hardware.ShakeDetector
import com.hippo.widget.lockpattern.LockPatternUtils
import com.hippo.widget.lockpattern.LockPatternView
import com.hippo.lib.yorozuya.ViewUtils
import com.lanraragi.reader.client.api.LRRSecureStorageUnavailableException

class SecurityScene : SolidScene(),
    LockPatternView.OnPatternListener, ShakeDetector.OnShakeListener {

    companion object {
        private const val TAG = "SecurityScene"
        private const val ERROR_TIMEOUT_MILLIS = 1200L
        private const val SUCCESS_DELAY_MILLIS = 100L
        private const val LOCKOUT_UPDATE_INTERVAL_MS = 1000L

        private const val KEY_RETRY_TIMES = "retry_times"
    }

    private lateinit var viewModel: SecurityViewModel

    private var mPatternView: LockPatternView? = null
    private lateinit var mFingerprintIcon: ImageView
    private var mLockoutText: TextView? = null

    private var mSensorManager: SensorManager? = null
    private var mAccelerometer: Sensor? = null
    private var mShakeDetector: ShakeDetector? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[SecurityViewModel::class.java]

        val context = ehContext ?: return
        val sensorMgr = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        mSensorManager = sensorMgr
        if (sensorMgr != null) {
            val accelerometer = sensorMgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            mAccelerometer = accelerometer
            if (accelerometer != null) {
                val detector = ShakeDetector()
                detector.setOnShakeListener(this)
                mShakeDetector = detector
            }
        }

        if (savedInstanceState != null) {
            viewModel.restoreRetryTimes(savedInstanceState.getInt(KEY_RETRY_TIMES))
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        mSensorManager = null
        mAccelerometer = null
        mShakeDetector = null
    }

    override fun onResume() {
        super.onResume()

        val shakeDetector = mShakeDetector
        if (shakeDetector != null) {
            mSensorManager?.registerListener(shakeDetector, mAccelerometer, SensorManager.SENSOR_DELAY_UI)
        }

        if (isFingerprintAuthAvailable()) {
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

        val shakeDetector = mShakeDetector
        if (shakeDetector != null) {
            mSensorManager?.unregisterListener(shakeDetector)
        }
        mBiometricPrompt?.cancelAuthentication()
        mHandler.removeCallbacks(mLockoutUpdateRunnable)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_RETRY_TIMES, viewModel.retryTimes.value)
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
                        if (ehContext != null && isAdded) {
                            startSceneForCheckStep(CHECK_STEP_SECURITY, arguments)
                            finish()
                        }
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

        return view
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
                if (ehContext != null && isAdded) {
                    startSceneForCheckStep(CHECK_STEP_SECURITY, arguments)
                    finish()
                }
            }

            is SecurityUiEvent.PatternFailed -> {
                val patternView = mPatternView ?: return
                patternView.setDisplayMode(LockPatternView.DisplayMode.Wrong)
                updateLockoutUi()
                if (viewModel.lockoutState.value.isLockedOut) {
                    mHandler.postDelayed(mLockoutUpdateRunnable, LOCKOUT_UPDATE_INTERVAL_MS)
                }
            }

            is SecurityUiEvent.RetriesExhausted -> {
                val patternView = mPatternView ?: return
                patternView.setDisplayMode(LockPatternView.DisplayMode.Wrong)
                updateLockoutUi()
                if (viewModel.lockoutState.value.isLockedOut) {
                    mHandler.postDelayed(mLockoutUpdateRunnable, LOCKOUT_UPDATE_INTERVAL_MS)
                }
                finish()
            }

            is SecurityUiEvent.NeedBiometricVerification -> {
                verifyWithBiometric(event.pattern)
            }
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
            mLockoutText?.text = getString(R.string.pattern_lockout_message, state.remainingSeconds)
        } else {
            mPatternView?.isEnabled = true
            mLockoutText?.visibility = View.GONE
        }
    }

    // -------------------------------------------------------------------------
    // Shake detector
    // -------------------------------------------------------------------------

    override fun onShake(count: Int) {
        if (count == 10) {
            val activity = activity2 ?: return
            try {
                SecuritySettings.setPattern("")
            } catch (e: LRRSecureStorageUnavailableException) {
                val ctx = ehContext ?: return
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.lrr_keystore_failed_title)
                    .setMessage(R.string.lrr_secure_storage_write_failed)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return
            }
            if (ehContext != null && isAdded) {
                startSceneForCheckStep(CHECK_STEP_SECURITY, arguments)
                finish()
            }
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
