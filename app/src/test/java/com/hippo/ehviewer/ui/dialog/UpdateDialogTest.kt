package com.hippo.ehviewer.ui.dialog

import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.settings.UpdateSettings
import com.hippo.ehviewer.updater.GhRelease
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/**
 * Unit tests for [UpdateDialog] button wiring. Robolectric inflates the AppCompat
 * AlertDialog against the app's [R.style.AppTheme]; the dialog is posted to the main
 * looper via ContextCompat.getMainExecutor, so tests idle the looper before asserting.
 *
 * Skip-version is reset to 0 around each test (key is private; reset via the public setter).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateDialogTest {

    private lateinit var controller: ActivityController<AppCompatActivity>
    private lateinit var activity: AppCompatActivity

    @Before
    fun setUp() {
        Settings.initialize(ApplicationProvider.getApplicationContext())
        UpdateSettings.putSkipUpdateVersion(0)
        controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        controller.create().start().resume()
    }

    @After
    fun tearDown() {
        UpdateSettings.putSkipUpdateVersion(0)
        controller.close()
    }

    @Test
    fun ignoreButtonPersistsSkipVersion() {
        // tagName v1.99.0 → versionCode 19900 (> 0, distinct from the default 0)
        val release = GhRelease(tagName = "v1.99.0", name = "v1.99.0", body = "release notes")
        assertEquals(19900, release.versionCode)

        UpdateDialog.showUpdateDialog(activity, release)
        ShadowLooper.idleMainLooper() // flush the getMainExecutor post that builds + shows the dialog

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.performClick()
        ShadowLooper.idleMainLooper() // AlertController dispatches button clicks via a Handler message

        assertEquals(19900, UpdateSettings.getSkipUpdateVersion())
    }
}
