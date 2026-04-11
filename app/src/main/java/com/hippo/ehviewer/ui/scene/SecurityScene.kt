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
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import com.hippo.ehviewer.R
import com.lanraragi.reader.client.api.LRRSecureStorageUnavailableException
import com.hippo.ehviewer.settings.SecuritySettings
import com.hippo.ehviewer.ui.SetSecurityActivity
import com.hippo.hardware.ShakeDetector
import com.hippo.widget.lockpattern.LockPatternUtils
import com.hippo.widget.lockpattern.LockPatternView
import com.hippo.lib.yorozuya.AssertUtils
import com.hippo.lib.yorozuya.ViewUtils

class SecurityScene : SolidScene(),
    LockPatternView.OnPatternListener, ShakeDetector.OnShakeListener {

    companion object {
        private const val MAX_RETRY_TIMES = 5
        private const val ERROR_TIMEOUT_MILLIS = 1200L
        private const val SUCCESS_DELAY_MILLIS = 100L

        private const val KEY_RETRY_TIMES = "retry_times"
    }

    private var mPatternView: LockPatternView? = null
    private lateinit var mFingerprintIcon: ImageView

    private var mSensorManager: SensorManager? = null
    private var mAccelerometer: Sensor? = null
    private var mShakeDetector: ShakeDetector? = null
    private var mFingerprintManager: FingerprintManager? = null

    private var mFingerprintCancellationSignal: CancellationSignal? = null

    private var mRetryTimes = 0

    override fun needShowLeftDrawer(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val context = ehContext
        AssertUtils.assertNotNull(context)
        mSensorManager = context!!.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (mSensorManager != null) {
            mAccelerometer = mSensorManager!!.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (mAccelerometer != null) {
                mShakeDetector = ShakeDetector()
                mShakeDetector!!.setOnShakeListener(this)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mFingerprintManager = context.getSystemService(FingerprintManager::class.java)
        }

        mRetryTimes = if (savedInstanceState == null) {
            MAX_RETRY_TIMES
        } else {
            savedInstanceState.getInt(KEY_RETRY_TIMES)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        mSensorManager = null
        mAccelerometer = null
        mShakeDetector = null
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()

        if (mShakeDetector != null) {
            mSensorManager!!.registerListener(mShakeDetector, mAccelerometer, SensorManager.SENSOR_DELAY_UI)
        }

        if (isFingerprintAuthAvailable()) {
            mFingerprintCancellationSignal = CancellationSignal()
            mFingerprintManager!!.authenticate(null, mFingerprintCancellationSignal, 0,
                object : FingerprintManager.AuthenticationCallback() {
                    override fun onAuthenticationError(errMsgId: Int, errString: CharSequence) {
                        fingerprintError(true)
                    }

                    override fun onAuthenticationFailed() {
                        fingerprintError(false)
                    }

                    override fun onAuthenticationSucceeded(result: FingerprintManager.AuthenticationResult) {
                        mFingerprintIcon.setImageResource(R.drawable.fingerprint_success)
                        mFingerprintIcon.postDelayed({
                            if (ehContext != null && isAdded) {
                                startSceneForCheckStep(CHECK_STEP_SECURITY, arguments)
                                finish()
                            }
                        }, SUCCESS_DELAY_MILLIS)
                    }
                }, null)
        }
    }

    override fun onPause() {
        super.onPause()

        if (mShakeDetector != null) {
            mSensorManager!!.unregisterListener(mShakeDetector)
        }
        if (isFingerprintAuthAvailable() && mFingerprintCancellationSignal != null) {
            mFingerprintCancellationSignal!!.cancel()
            mFingerprintCancellationSignal = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_RETRY_TIMES, mRetryTimes)
    }

    override fun onCreateView2(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.scene_security, container, false)

        mPatternView = ViewUtils.`$$`(view, R.id.pattern_view) as LockPatternView
        mPatternView!!.setOnPatternListener(this)

        mFingerprintIcon = ViewUtils.`$$`(view, R.id.fingerprint_icon) as ImageView
        if (SecuritySettings.getEnableFingerprint() && isFingerprintAuthAvailable()) {
            mFingerprintIcon.visibility = View.VISIBLE
            mFingerprintIcon.setImageResource(R.drawable.ic_fp_40px)
        }
        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()

        mPatternView = null
    }

    override fun onPatternStart() {}

    override fun onPatternCleared() {}

    override fun onPatternCellAdded(pattern: List<LockPatternView.Cell>) {}

    override fun onPatternDetected(pattern: List<LockPatternView.Cell>) {
        val activity = activity2 ?: return
        val patternView = mPatternView ?: return

        val enteredPattern = LockPatternUtils.patternToString(pattern)

        if (SecuritySettings.verifyPattern(enteredPattern)) {
            if (ehContext != null && isAdded) {
                startSceneForCheckStep(CHECK_STEP_SECURITY, arguments)
                finish()
            }
        } else {
            patternView.setDisplayMode(LockPatternView.DisplayMode.Wrong)
            mRetryTimes--
            if (mRetryTimes <= 0) {
                finish()
            }
        }
    }

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

    @Suppress("DEPRECATION")
    private fun isFingerprintAuthAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            SecuritySettings.getEnableFingerprint() &&
            mFingerprintManager != null &&
            SetSecurityActivity.hasEnrolledFingerprints(mFingerprintManager!!)
    }

    private val mResetFingerprintRunnable = Runnable {
        mFingerprintIcon.setImageResource(R.drawable.ic_fp_40px)
    }

    private fun fingerprintError(unrecoverable: Boolean) {
        // Do not decrease mRetryTimes here since Android system will handle it :)
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
