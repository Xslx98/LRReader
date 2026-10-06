package com.lanraragi.reader.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BackupSettingsTest {

    private fun prefs(name: String) = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test
    fun the_lock_and_the_download_location_never_travel() {
        for (key in listOf("security", "enable_fingerprint", "image_scheme", "image_path", "version_code")) {
            assertFalse(key, key in BackupSettings.KEYS)
        }
    }

    @Test
    fun whitelisted_values_round_trip_with_their_types() {
        val from = prefs("settings_from")
        from.edit()
            .putInt("theme", 2).putBoolean("keep_screen_on", true).putString("proxy_ip", "10.0.0.1")
            .putFloat("screen_lightness", 0.5f).putString("image_path", "/sdcard/x").commit()

        val exported = BackupSettings.export(from)
        assertFalse(exported.any { it.key == "image_path" })

        val to = prefs("settings_to")
        assertEquals(4, BackupSettings.apply(to, exported))
        assertEquals(2, to.getInt("theme", 0))
        assertTrue(to.getBoolean("keep_screen_on", false))
        assertEquals("10.0.0.1", to.getString("proxy_ip", null))
        assertEquals(0.5f, to.getFloat("screen_lightness", 0f))
    }

    @Test
    fun keys_outside_the_whitelist_and_bad_values_are_ignored() {
        val to = prefs("settings_bad")
        val applied = BackupSettings.apply(
            to,
            listOf(
                BackupSetting("security", BackupSettings.TYPE_STRING, "hash"),
                BackupSetting("theme", BackupSettings.TYPE_INT, "not a number"),
            ),
        )
        assertEquals(0, applied)
        assertFalse(to.contains("security"))
        assertFalse(to.contains("theme"))
    }
}
