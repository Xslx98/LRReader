package com.lanraragi.reader.client.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class PrefsServerCapabilityStoreTest {

    private lateinit var store: PrefsServerCapabilityStore

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val prefs = ctx.getSharedPreferences("cap_store_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = PrefsServerCapabilityStore(prefs)
    }

    @Test
    fun unknownServer_isNull() {
        assertNull(store.loadExcludedNamespaces("http://a"))
    }

    @Test
    fun roundTrip_perServer() {
        store.saveExcludedNamespaces("http://a", setOf("date_added", "source"))
        store.saveExcludedNamespaces("http://b", setOf("timestamp"))

        assertEquals(setOf("date_added", "source"), store.loadExcludedNamespaces("http://a"))
        assertEquals(setOf("timestamp"), store.loadExcludedNamespaces("http://b"))
    }

    @Test
    fun observedEmptyList_isEmptySet_notNull() {
        // "The server told us nothing is excluded" must not read back as
        // "never asked", or the stats page would keep re-fetching /api/info.
        store.saveExcludedNamespaces("http://a", emptySet())
        assertEquals(emptySet<String>(), store.loadExcludedNamespaces("http://a"))
    }
}
