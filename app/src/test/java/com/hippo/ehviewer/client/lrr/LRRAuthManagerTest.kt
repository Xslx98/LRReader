package com.hippo.ehviewer.client.lrr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [LRRAuthManager].
 *
 * Uses Robolectric because [LRRAuthManager.initialize] creates [EncryptedSharedPreferences]
 * which requires an Android Context. The test Application is a plain [android.app.Application]
 * so the Android KeyStore is unavailable in this environment — [LRRAuthManager.initialize]
 * will set sPrefs=null. We always recover via [LRRAuthManager.initializeForTesting] which
 * injects a plain SharedPreferences so that all credential round-trip tests remain meaningful.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class LRRAuthManagerTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        LRRAuthManager.initialize(ctx)
        // In Robolectric, Android KeyStore is unavailable so EncryptedSharedPreferences
        // initialization fails and sPrefs is left null. Always inject a plain
        // SharedPreferences so the remaining tests can exercise credential storage logic.
        // (With improved degradation detection, isNeedsReauthentication() is false for
        // fresh installs even when sPrefs is null, so we always inject here.)
        LRRAuthManager.initializeForTesting(
            ctx.getSharedPreferences("lrr_auth_test", android.content.Context.MODE_PRIVATE)
        )
    }

    @After
    fun tearDown() {
        LRRAuthManager.clear()
    }

    // ── Server URL ──────────────────────────────────────────────────

    @Test
    fun setAndGetServerUrl_roundtrips() {
        LRRAuthManager.setServerUrl("http://192.168.1.100:3000")
        assertEquals("http://192.168.1.100:3000", LRRAuthManager.getServerUrl())
    }

    @Test
    fun setServerUrl_stripsTrailingSlash() {
        LRRAuthManager.setServerUrl("http://192.168.1.100:3000/")
        assertEquals("http://192.168.1.100:3000", LRRAuthManager.getServerUrl())
    }

    @Test
    fun isConfigured_falseWhenNoUrl() {
        assertFalse(LRRAuthManager.isConfigured())
    }

    @Test
    fun isConfigured_trueAfterSettingUrl() {
        LRRAuthManager.setServerUrl("http://192.168.1.100:3000")
        assertTrue(LRRAuthManager.isConfigured())
    }

    // ── API key ─────────────────────────────────────────────────────

    @Test
    fun setAndGetApiKey_roundtrips() {
        LRRAuthManager.setApiKey("abc123")
        assertEquals("abc123", LRRAuthManager.getApiKey())
    }

    @Test
    fun setApiKey_nullClearsKey() {
        LRRAuthManager.setApiKey("abc123")
        LRRAuthManager.setApiKey(null)
        assertNull(LRRAuthManager.getApiKey())
    }

    // ── Per-profile API key ─────────────────────────────────────────

    @Test
    fun setAndGetApiKeyForProfile_roundtrips() {
        LRRAuthManager.setApiKeyForProfile(42L, "profile-key")
        assertEquals("profile-key", LRRAuthManager.getApiKeyForProfile(42L))
    }

    @Test
    fun clearApiKeyForProfile_removesKey() {
        LRRAuthManager.setApiKeyForProfile(42L, "profile-key")
        LRRAuthManager.clearApiKeyForProfile(42L)
        assertNull(LRRAuthManager.getApiKeyForProfile(42L))
    }

    @Test
    fun profileKeysAreIsolated() {
        LRRAuthManager.setApiKeyForProfile(1L, "key-1")
        LRRAuthManager.setApiKeyForProfile(2L, "key-2")
        assertEquals("key-1", LRRAuthManager.getApiKeyForProfile(1L))
        assertEquals("key-2", LRRAuthManager.getApiKeyForProfile(2L))
    }

    // ── Active profile ID ───────────────────────────────────────────

    @Test
    fun setAndGetActiveProfileId_roundtrips() {
        LRRAuthManager.setActiveProfileId(99L)
        assertEquals(99L, LRRAuthManager.getActiveProfileId())
    }

    // ── Pattern hash/verify ─────────────────────────────────────────

    @Test
    fun hasPattern_falseBeforeSet() {
        assertFalse(LRRAuthManager.hasPattern())
    }

    @Test
    fun setPattern_thenHasPattern() {
        LRRAuthManager.setPattern("1234")
        assertTrue(LRRAuthManager.hasPattern())
    }

    @Test
    fun verifyPattern_correctInput_returnsTrue() {
        LRRAuthManager.setPattern("secret")
        assertTrue(LRRAuthManager.verifyPattern("secret"))
    }

    @Test
    fun verifyPattern_wrongInput_returnsFalse() {
        LRRAuthManager.setPattern("secret")
        assertFalse(LRRAuthManager.verifyPattern("wrong"))
    }

    @Test
    fun verifyPattern_emptyInput_returnsFalse() {
        LRRAuthManager.setPattern("secret")
        assertFalse(LRRAuthManager.verifyPattern(""))
    }

    @Test
    fun verifyPattern_nullInput_returnsFalse() {
        LRRAuthManager.setPattern("secret")
        assertFalse(LRRAuthManager.verifyPattern(null))
    }

    @Test
    fun setPattern_null_clearsPattern() {
        LRRAuthManager.setPattern("secret")
        LRRAuthManager.setPattern(null)
        assertFalse(LRRAuthManager.hasPattern())
        assertFalse(LRRAuthManager.verifyPattern("secret"))
    }

    @Test
    fun setPattern_overwritesPriorHash_stillVerifiesCorrectly() {
        // Same pattern stored twice should still verify correctly (different salts but same result)
        LRRAuthManager.setPattern("same")
        assertTrue(LRRAuthManager.verifyPattern("same"))
        LRRAuthManager.setPattern("same")
        assertTrue(LRRAuthManager.verifyPattern("same"))
    }

    @Test
    fun isNeedsReauthentication_falseAfterInitializeForTesting() {
        assertFalse("Reauth flag should be false when KeyStore is available",
            LRRAuthManager.isNeedsReauthentication())
    }

    @Test
    fun getServerUrl_returnsNullWhenNotSet() {
        assertNull(LRRAuthManager.getServerUrl())
    }

    @Test
    fun setServerUrl_persistsAndRetrievable() {
        LRRAuthManager.setServerUrl("http://test.local:3000")
        assertNotNull(LRRAuthManager.getServerUrl())
    }

    @Test
    fun clear_removesAllCredentials() {
        LRRAuthManager.setServerUrl("http://test.local")
        LRRAuthManager.setApiKey("key")
        LRRAuthManager.setActiveProfileId(5L)
        LRRAuthManager.setPattern("pin")
        LRRAuthManager.clear()
        assertNull(LRRAuthManager.getServerUrl())
        assertNull(LRRAuthManager.getApiKey())
        assertEquals(0L, LRRAuthManager.getActiveProfileId())
        assertFalse(LRRAuthManager.hasPattern())
    }

    // ── Setter failure surfacing (sPrefs == null) ──────────────────
    //
    // When the KeyStore / EncryptedSharedPreferences is unavailable every setter
    // must throw LRRSecureStorageUnavailableException instead of silently dropping
    // the write. Silent drops previously let users believe they had saved new
    // credentials when in reality nothing was persisted.

    @Test(expected = LRRSecureStorageUnavailableException::class)
    fun setServerUrl_throwsWhenStorageUnavailable() {
        LRRAuthManager.simulateStorageUnavailableForTesting()
        LRRAuthManager.setServerUrl("http://test.local")
    }

    @Test(expected = LRRSecureStorageUnavailableException::class)
    fun setApiKey_throwsWhenStorageUnavailable() {
        LRRAuthManager.simulateStorageUnavailableForTesting()
        LRRAuthManager.setApiKey("secret")
    }

    @Test(expected = LRRSecureStorageUnavailableException::class)
    fun setServerName_throwsWhenStorageUnavailable() {
        LRRAuthManager.simulateStorageUnavailableForTesting()
        LRRAuthManager.setServerName("LRR")
    }

    @Test(expected = LRRSecureStorageUnavailableException::class)
    fun setActiveProfileId_throwsWhenStorageUnavailable() {
        LRRAuthManager.simulateStorageUnavailableForTesting()
        LRRAuthManager.setActiveProfileId(7L)
    }

    @Test(expected = LRRSecureStorageUnavailableException::class)
    fun setApiKeyForProfile_throwsWhenStorageUnavailable() {
        LRRAuthManager.simulateStorageUnavailableForTesting()
        LRRAuthManager.setApiKeyForProfile(1L, "key")
    }

    @Test(expected = LRRSecureStorageUnavailableException::class)
    fun clearApiKeyForProfile_throwsWhenStorageUnavailable() {
        LRRAuthManager.simulateStorageUnavailableForTesting()
        LRRAuthManager.clearApiKeyForProfile(1L)
    }

    @Test(expected = LRRSecureStorageUnavailableException::class)
    fun setPattern_throwsWhenStorageUnavailable() {
        LRRAuthManager.simulateStorageUnavailableForTesting()
        LRRAuthManager.setPattern("1234")
    }

    @Test
    fun verifyPattern_returnsFalseWhenStorageUnavailable() {
        // Reads MUST NOT throw — verify-before-unlock flows need to degrade gracefully.
        LRRAuthManager.simulateStorageUnavailableForTesting()
        assertFalse(LRRAuthManager.verifyPattern("anything"))
    }

    @Test
    fun getters_returnNullWhenStorageUnavailable() {
        // Reads MUST NOT throw — UI polls getApiKey()/getServerUrl() frequently.
        LRRAuthManager.simulateStorageUnavailableForTesting()
        assertNull(LRRAuthManager.getServerUrl())
        assertNull(LRRAuthManager.getApiKey())
        assertNull(LRRAuthManager.getServerName())
        assertNull(LRRAuthManager.getApiKeyForProfile(1L))
        assertFalse(LRRAuthManager.hasPattern())
    }

    @Test
    fun clear_toleratesUnavailableStorage() {
        // clear() is safe to call during reauth flows — must not throw even if
        // the backing store was never initialized.
        LRRAuthManager.simulateStorageUnavailableForTesting()
        LRRAuthManager.clear() // should not throw
        assertEquals(0L, LRRAuthManager.getActiveProfileId())
    }
}
