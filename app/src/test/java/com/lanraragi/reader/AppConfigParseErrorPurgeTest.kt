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

/**
 * Audit C49 / SEC-19: raw server responses and old crash/logcat dumps (with
 * SERIAL/HOST/USER) no longer linger in external storage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class AppConfigParseErrorPurgeTest {

    private fun dirWithFile(parent: File?, name: String): File =
        File(parent, name).apply {
            mkdirs()
            File(this, "2026-01-01.txt").writeText("legacy dump")
        }

    @Test
    fun legacyExternalDumpDirsAreDeleted_otherDataKept() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        AppConfig.initialize(ctx)
        val external = ctx.getExternalFilesDir(null)
        val parseError = dirWithFile(external, "parse_error")
        val logcat = dirWithFile(external, "logcat")
        val externalCrash = dirWithFile(external, "crash")
        val download = dirWithFile(external, "download")
        // The current crash store is app-private and must survive.
        val privateCrash = dirWithFile(ctx.filesDir, "crash")

        AppConfig.purgeLegacyExternalDumpDirs()

        assertFalse(parseError.exists())
        assertFalse(logcat.exists())
        assertFalse(externalCrash.exists())
        assertTrue(File(download, "2026-01-01.txt").exists())
        assertTrue(File(privateCrash, "2026-01-01.txt").exists())
    }

    @Test
    fun purgeWithNothingToDelete_isANoOp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        AppConfig.initialize(ctx)
        AppConfig.purgeLegacyExternalDumpDirs()
        assertFalse(File(ctx.getExternalFilesDir(null), "logcat").exists())
    }
}
