package com.lanraragi.reader.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A store built later over the same preferences reads back what an earlier
 * one saved: that is what carries the interrupted-download record (A47) to
 * the next process. Process death itself is not simulated here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class PrefsInterruptedStoreTest {

    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("interrupted_store_test", Context.MODE_PRIVATE)

    @Test
    fun savedArcidsAreReadBackByALaterStore() {
        PrefsInterruptedStore(prefs).save(setOf("a", "b"))

        assertEquals(setOf("a", "b"), PrefsInterruptedStore(prefs).load())

        PrefsInterruptedStore(prefs).save(emptySet())
        assertEquals(emptySet<String>(), PrefsInterruptedStore(prefs).load())
    }
}
