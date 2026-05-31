package com.hippo.ehviewer.ui.fragment

import androidx.appcompat.app.AppCompatActivity
import androidx.preference.SwitchPreference
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.settings.UpdateSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Verifies the "auto-check for updates" [SwitchPreference] in [AboutFragment] renders a
 * checked state that matches [UpdateSettings.getAutoCheckUpdates] — the authoritative
 * (single source of truth) default. The XML preference intentionally carries no
 * android:defaultValue (project convention: modular settings objects own the default),
 * so the fragment must bind the widget from the getter, otherwise the toggle renders OFF
 * while the cold-start auto-check actually runs (the divergence this test pins down).
 *
 * The fragment is hosted in a bare themed AppCompatActivity and only driven to the CREATED
 * state: PreferenceFragmentCompat runs onCreatePreferences() from onCreate(), which is where
 * the binding happens. The view is never created/destroyed, so we avoid
 * BasePreferenceFragmentCompat.onDestroyView()'s hard cast to SettingsActivity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class AboutFragmentAutoCheckTest {

    private lateinit var controller: ActivityController<AppCompatActivity>
    private lateinit var activity: AppCompatActivity

    @Before
    fun setUp() {
        Settings.initialize(ApplicationProvider.getApplicationContext())
        Settings.getPreferences().edit().remove(UpdateSettings.KEY_AUTO_CHECK_UPDATES).apply()
        controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        controller.create()
    }

    @After
    fun tearDown() {
        Settings.getPreferences().edit().remove(UpdateSettings.KEY_AUTO_CHECK_UPDATES).apply()
    }

    private fun hostAboutFragment(): AboutFragment {
        val fragment = AboutFragment()
        activity.supportFragmentManager.beginTransaction()
            .add(fragment, "about")
            .commitNow()
        ShadowLooper.idleMainLooper()
        return fragment
    }

    private fun autoCheckSwitch(fragment: AboutFragment): SwitchPreference =
        requireNotNull(fragment.findPreference(UpdateSettings.KEY_AUTO_CHECK_UPDATES))

    @Test
    fun freshInstallSwitchRendersOnMatchingDefault() {
        // Fresh install: key unpersisted, getter falls back to the enabled default.
        assertTrue("precondition: getter defaults true", UpdateSettings.getAutoCheckUpdates())

        val fragment = hostAboutFragment()

        assertTrue(
            "auto-check switch must render ON to match getAutoCheckUpdates()",
            autoCheckSwitch(fragment).isChecked,
        )
    }

    @Test
    fun persistedDisabledSwitchRendersOff() {
        UpdateSettings.putAutoCheckUpdates(false)

        val fragment = hostAboutFragment()

        assertFalse(autoCheckSwitch(fragment).isChecked)
    }

    @Test
    fun switchAlwaysMatchesGetter() {
        val fragment = hostAboutFragment()

        assertEquals(
            UpdateSettings.getAutoCheckUpdates(),
            autoCheckSwitch(fragment).isChecked,
        )
    }
}
