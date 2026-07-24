package com.lanraragi.reader.client.api

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks the in-memory profile-key cache contract of
 * [LRRAuthManager.getApiKeyForProfile]: the backing store (in production an
 * EncryptedSharedPreferences that pays an AES-GCM decrypt per getString) is
 * hit at most once per profile, and every credential mutation invalidates.
 * LRRAuthInterceptor resolves the key on EVERY authenticated request — page
 * and thumbnail fetches included — so repeated store reads are a hot-path
 * crypto cost, not just a lookup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class LRRAuthManagerKeyCacheTest {

    /** Delegating prefs that counts getString hits on the backing store. */
    private class CountingPrefs(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        var getStringCalls = 0
        override fun getString(key: String?, defValue: String?): String? {
            getStringCalls++
            return delegate.getString(key, defValue)
        }
    }

    private lateinit var counting: CountingPrefs

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        LRRAuthManager.initialize(context)
        val real = context.getSharedPreferences("lrr_auth_keycache_test", Context.MODE_PRIVATE)
        real.edit().clear().commit()
        counting = CountingPrefs(real)
        LRRAuthManager.initializeForTesting(counting)
    }

    @After
    fun tearDown() {
        LRRAuthManager.clear()
    }

    @Test
    fun getApiKeyForProfile_hitsBackingStoreOnceThenServesFromCache() {
        LRRAuthManager.setApiKeyForProfile(7L, "secret-7")
        counting.getStringCalls = 0

        assertEquals("secret-7", LRRAuthManager.getApiKeyForProfile(7L))
        assertEquals("secret-7", LRRAuthManager.getApiKeyForProfile(7L))
        assertEquals("secret-7", LRRAuthManager.getApiKeyForProfile(7L))

        assertEquals(
            "repeated gets must not re-read (re-decrypt) the backing store",
            0, counting.getStringCalls
        )
    }

    @Test
    fun getApiKeyForProfile_cachesTheNoKeyState() {
        assertNull(LRRAuthManager.getApiKeyForProfile(8L))
        val callsAfterFirst = counting.getStringCalls
        assertNull(LRRAuthManager.getApiKeyForProfile(8L))
        assertEquals(
            "the absent/empty-key state must be cached too",
            callsAfterFirst, counting.getStringCalls
        )
    }

    @Test
    fun setApiKeyForProfile_updatesServedValueImmediately() {
        LRRAuthManager.setApiKeyForProfile(9L, "old")
        assertEquals("old", LRRAuthManager.getApiKeyForProfile(9L))

        LRRAuthManager.setApiKeyForProfile(9L, "new")
        assertEquals("new", LRRAuthManager.getApiKeyForProfile(9L))
    }

    @Test
    fun clearApiKeyForProfile_invalidates() {
        LRRAuthManager.setApiKeyForProfile(10L, "gone-soon")
        assertEquals("gone-soon", LRRAuthManager.getApiKeyForProfile(10L))

        LRRAuthManager.clearApiKeyForProfile(10L)
        assertNull(LRRAuthManager.getApiKeyForProfile(10L))
    }

    @Test
    fun clear_dropsAllCachedKeys() {
        LRRAuthManager.setApiKeyForProfile(11L, "k11")
        assertEquals("k11", LRRAuthManager.getApiKeyForProfile(11L))

        LRRAuthManager.clear()

        assertNull(LRRAuthManager.getApiKeyForProfile(11L))
    }

    @Test
    fun initializeForTesting_resetsCacheAcrossReinit() {
        LRRAuthManager.setApiKeyForProfile(12L, "stale")
        assertEquals("stale", LRRAuthManager.getApiKeyForProfile(12L))

        // Re-init against a fresh empty store: the old cached key must not
        // leak through (singleton object shares state across tests).
        val context: Context = ApplicationProvider.getApplicationContext()
        val fresh = context.getSharedPreferences("lrr_auth_keycache_fresh", Context.MODE_PRIVATE)
        fresh.edit().clear().commit()
        LRRAuthManager.initializeForTesting(fresh)

        assertNull(LRRAuthManager.getApiKeyForProfile(12L))
    }
}
