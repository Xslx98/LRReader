package com.lanraragi.reader.client

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.AppConfig
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.module.CoroutineModule
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit C26 (ruling R10): the third-party tag dataset is fetched only while
 * tag translations are shown (Chinese locale + setting on). With the gate
 * closed the update still loads the local copy but never consults the
 * remote host — the throttle sees no attempt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class TagTranslationRemoteGateTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppConfig.initialize(context)
        ServiceRegistry.initializeForTest(CoroutineModule())
    }

    @After
    fun tearDown() {
        TagTranslationDatabase.remoteFetchAllowed = { com.lanraragi.reader.settings.AppearanceSettings.getShowTagTranslations() }
        TagTranslationDatabase.updateThrottle = null
    }

    @Test(timeout = 10_000)
    fun translations_off_never_contacts_the_dataset_host() = runBlocking {
        val throttle = TagDbUpdateThrottle(
            context.getSharedPreferences("tag_gate_test", Context.MODE_PRIVATE)
        )
        TagTranslationDatabase.updateThrottle = throttle
        TagTranslationDatabase.remoteFetchAllowed = { false }

        val job = TagTranslationDatabase.update(context)
        assertNotNull(job)
        job!!.join()

        assertTrue("remote check was attempted with translations off", throttle.shouldAttempt())
    }

    /**
     * Audit SEC-01: a local dataset whose SHA-1 matches but whose rows are
     * malformed is not activated and is deleted, so a later update can
     * fetch a replacement; meanwhile the app runs without translations.
     */
    @Test(timeout = 10_000)
    fun malformed_local_dataset_is_deleted_and_not_activated() = runBlocking {
        TagTranslationDatabase.instance = null
        TagTranslationDatabase.updateThrottle = TagDbUpdateThrottle(
            context.getSharedPreferences("tag_gate_test_malformed", Context.MODE_PRIVATE)
        )
        TagTranslationDatabase.remoteFetchAllowed = { false }

        val dir = AppConfig.getFilesDir("tag-translations")!!
        val dataFile = File(dir, "tag-translations-zh-rCN")
        val sha1File = File(dir, "tag-translations-zh-rCN.sha1")
        // A row without the '\r' separator and no trailing '\n'.
        val body = "a:asanagi".toByteArray()
        val content = Buffer().writeInt(body.size).write(body).readByteArray()
        dataFile.writeBytes(content)
        sha1File.writeBytes(MessageDigest.getInstance("SHA-1").digest(content))

        TagTranslationDatabase.update(context)!!.join()

        assertNull(TagTranslationDatabase.getInstance(context))
        assertFalse("malformed dataset kept on disk", dataFile.exists())
        assertFalse("sha1 of the malformed dataset kept on disk", sha1File.exists())
    }
}
