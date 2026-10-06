package com.lanraragi.reader.ui

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Audit 2026-10-06b PERF-02: the reader used to declare only
 * `screenSize|uiMode`, so a rotation, a foldable unfold or a multi-window
 * resize destroyed and recreated GalleryActivity. onDestroy calls
 * provider.stop(), which drops the decoded-page LRU and every GL texture,
 * and an online session redoes its metadata request and clearNewFlag.
 *
 * The system recreates an activity unless it declares EVERY bit that
 * changed (Configuration.diff), so the test diffs real before/after
 * configurations instead of listing flags by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class GalleryActivityConfigChangesTest {

    private fun readerConfigChanges(): Int {
        val app = RuntimeEnvironment.getApplication()
        val info: ActivityInfo = app.packageManager.getActivityInfo(
            ComponentName(app, GalleryActivity::class.java), PackageManager.GET_META_DATA
        )
        return info.configChanges
    }

    private fun config(widthDp: Int, heightDp: Int, smallestDp: Int = minOf(widthDp, heightDp)) =
        Configuration().apply {
            setToDefaults()
            screenWidthDp = widthDp
            screenHeightDp = heightDp
            smallestScreenWidthDp = smallestDp
            orientation = if (widthDp > heightDp) {
                Configuration.ORIENTATION_LANDSCAPE
            } else {
                Configuration.ORIENTATION_PORTRAIT
            }
            screenLayout = if (smallestDp >= 600) {
                Configuration.SCREENLAYOUT_SIZE_LARGE or Configuration.SCREENLAYOUT_LONG_NO
            } else {
                Configuration.SCREENLAYOUT_SIZE_NORMAL or Configuration.SCREENLAYOUT_LONG_YES
            }
        }

    private fun assertHandledInPlace(what: String, from: Configuration, to: Configuration) {
        val changed = from.diff(to)
        val declared = readerConfigChanges()
        assertEquals(
            "$what would recreate GalleryActivity: undeclared bits " +
                Integer.toHexString(changed and declared.inv()),
            changed, changed and declared,
        )
    }

    @Test
    fun `phone rotation is handled in place`() {
        assertHandledInPlace("rotation", config(411, 891), config(891, 411))
    }

    @Test
    fun `foldable unfold is handled in place`() {
        assertHandledInPlace("unfold", config(411, 891), config(841, 701))
    }

    @Test
    fun `multi-window split resize is handled in place`() {
        // Half of a portrait phone: the window turns landscape-shaped.
        assertHandledInPlace("split resize", config(411, 891), config(411, 380, smallestDp = 411))
    }

    @Test
    fun `hardware keyboard attach is handled in place`() {
        val docked = Configuration(config(411, 891)).apply {
            keyboard = Configuration.KEYBOARD_QWERTY
            keyboardHidden = Configuration.KEYBOARDHIDDEN_NO
            hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO
            navigation = Configuration.NAVIGATION_DPAD
        }
        assertHandledInPlace("keyboard attach", config(411, 891), docked)
    }
}
