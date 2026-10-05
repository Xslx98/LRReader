package com.lanraragi.reader.client

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.AppConfig
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.module.CoroutineModule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
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
}
