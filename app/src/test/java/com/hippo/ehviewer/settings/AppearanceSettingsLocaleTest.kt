package com.hippo.ehviewer.settings

import android.content.res.Resources
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Tests for [AppearanceSettings.resolveAppLocale] — the language-string → [Locale]
 * resolution shared by `EhApplication.attachBaseContext` and
 * `EhActivity.attachBaseContext`. A fresh process/activity reads the persisted
 * `app_language` value and resolves it here, so this is the logic that decides
 * which locale the whole UI (and the app-context strings behind GetText /
 * notifications / services) uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class AppearanceSettingsLocaleTest {

    @Test
    fun languageTag_withRegion_resolvesToLanguageAndCountry() {
        assertEquals(Locale("zh", "CN"), AppearanceSettings.resolveAppLocale("zh-CN"))
    }

    @Test
    fun languageTag_languageOnly_resolvesToLanguage() {
        assertEquals(Locale("ja"), AppearanceSettings.resolveAppLocale("ja"))
    }

    @Test
    fun languageTag_withVariant_resolvesAllThreeParts() {
        assertEquals(Locale("de", "DE", "POSIX"), AppearanceSettings.resolveAppLocale("de-DE-POSIX"))
    }

    @Test
    fun system_fallsBackToSystemLocale() {
        // "system" is the sentinel for "follow the device locale" — it must NOT be
        // parsed as a language tag (Locale("system")).
        assertEquals(
            Resources.getSystem().configuration.locale,
            AppearanceSettings.resolveAppLocale("system")
        )
    }

    @Test
    fun nullLanguage_fallsBackToSystemLocale() {
        assertEquals(
            Resources.getSystem().configuration.locale,
            AppearanceSettings.resolveAppLocale(null)
        )
    }
}
