package com.lanraragi.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Audit C49 / SEC-19: raw server responses no longer linger in external storage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class AppConfigParseErrorPurgeTest {

    @Test
    fun legacyParseErrorDirIsDeleted() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        AppConfig.initialize(ctx)
        val dir = File(ctx.getExternalFilesDir(null), "parse_error").apply { mkdirs() }
        File(dir, "2026-01-01.txt").writeText("raw server body")
        assertTrue(dir.exists())

        AppConfig.purgeLegacyParseErrorDir()

        assertFalse(dir.exists())
    }
}
