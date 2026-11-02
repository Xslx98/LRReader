package com.lanraragi.reader.server

import com.lanraragi.reader.client.api.*
import com.lanraragi.reader.client.api.data.*
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.lanraragi.reader.security.AppLockCredentials

/**
 * Unit tests for [LRRAuthManager].
 *
 * Uses Robolectric because [SecureCredentialStore.initialize] creates [EncryptedSharedPreferences]
 * which requires an Android Context. The test Application is a plain [android.app.Application]
 * so the Android KeyStore is unavailable in this environment — [SecureCredentialStore.initialize]
 * will set sPrefs=null. We always recover via [SecureCredentialStore.initializeForTesting] which
 * injects a plain SharedPreferences so that all credential round-trip tests remain meaningful.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class LRRAuthManagerTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        SecureCredentialStore.initialize(ctx)
        // In Robolectric, Android KeyStore is unavailable so EncryptedSharedPreferences
        // initialization fails and sPrefs is left null. Always inject a plain
        // SharedPreferences so the remaining tests can exercise credential storage logic.
        // (With improved degradation detection, isNeedsReauthentication() is false for
        // fresh installs even when sPrefs is null, so we always inject here.)
        SecureCredentialStore.initializeForTesting(
            ctx.getSharedPreferences("lrr_auth_test", android.content.Context.MODE_PRIVATE)
        )
    }

    @After
    fun tearDown() {
        SecureCredentialStore.clear()
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
    fun clearApiKeyForProfile_removesOnlyThatProfilesKey() {
        LRRAuthManager.setApiKeyForProfile(41L, "keep-me")
        LRRAuthManager.setApiKeyForProfile(42L, "profile-key")
        LRRAuthManager.clearApiKeyForProfile(42L)
        assertNull(LRRAuthManager.getApiKeyForProfile(42L))
        assertEquals("keep-me", LRRAuthManager.getApiKeyForProfile(41L))
    }

    @Test
    fun setApiKeyForProfile_emptyString_readsAsNoKey() {
        LRRAuthManager.setApiKeyForProfile(5L, "some_key")
        LRRAuthManager.setApiKeyForProfile(5L, "")
        assertNull(LRRAuthManager.getApiKeyForProfile(5L))
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
    fun clear_removesAllCredentials() {
        LRRAuthManager.setServerUrl("http://test.local")
        LRRAuthManager.setApiKey("key")
        LRRAuthManager.setActiveProfileId(5L)
        AppLockCredentials.setPattern("pin")
        SecureCredentialStore.clear()
        assertNull(LRRAuthManager.getServerUrl())
        assertNull(LRRAuthManager.getApiKey())
        assertEquals(0L, LRRAuthManager.getActiveProfileId())
        assertFalse(AppLockCredentials.hasPattern())
    }

    // ── Allow cleartext flag ────────────────────────────────────────

    @Test
    fun allowCleartext_defaultsToTrueWhenPrefsAvailable() {
        // Default value (no key in prefs, sPrefs available) — existing HTTP profiles
        // never wrote this key explicitly, so default must be true for compatibility
        assertTrue("default should be true when sPrefs available", LRRAuthManager.getAllowCleartext())
    }

    @Test
    fun setAllowCleartext_roundTripsThroughPrefs() {
        LRRAuthManager.setAllowCleartext(false)
        assertFalse("after setAllowCleartext(false)", LRRAuthManager.getAllowCleartext())

        LRRAuthManager.setAllowCleartext(true)
        assertTrue("after setAllowCleartext(true)", LRRAuthManager.getAllowCleartext())
    }

    @Test
    fun allowCleartext_returnsFalseWhenStorageUnavailable() {
        // When sPrefs is null (KeyStore broken), getAllowCleartext() must fail-closed
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        assertFalse(
            "getAllowCleartext must return false when secure storage is unavailable",
            LRRAuthManager.getAllowCleartext()
        )
    }

    // ── Setter failure surfacing (sPrefs == null) ──────────────────
    //
    // When the KeyStore / EncryptedSharedPreferences is unavailable every setter
    // must throw LRRSecureStorageUnavailableException instead of silently dropping
    // the write. Silent drops previously let users believe they had saved new
    // credentials when in reality nothing was persisted.

    @Test(expected = LRRSecureStorageUnavailableException::class)
    fun setServerUrl_throwsWhenStorageUnavailable() {
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        LRRAuthManager.setServerUrl("http://test.local")
    }

    @Test(expected = LRRSecureStorageUnavailableException::class)
    fun setApiKey_throwsWhenStorageUnavailable() {
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        LRRAuthManager.setApiKey("secret")
    }

    @Test(expected = LRRSecureStorageUnavailableException::class)
    fun setServerName_throwsWhenStorageUnavailable() {
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        LRRAuthManager.setServerName("LRR")
    }

    @Test(expected = LRRSecureStorageUnavailableException::class)
    fun setActiveProfileId_throwsWhenStorageUnavailable() {
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        LRRAuthManager.setActiveProfileId(7L)
    }

    @Test(expected = LRRSecureStorageUnavailableException::class)
    fun setApiKeyForProfile_throwsWhenStorageUnavailable() {
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        LRRAuthManager.setApiKeyForProfile(1L, "key")
    }

    @Test(expected = LRRSecureStorageUnavailableException::class)
    fun clearApiKeyForProfile_throwsWhenStorageUnavailable() {
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        LRRAuthManager.clearApiKeyForProfile(1L)
    }

    @Test(expected = LRRSecureStorageUnavailableException::class)
    fun setAllowCleartext_throwsWhenStorageUnavailable() {
        // Added during W0-3/W0-4 merge: setAllowCleartext originally used the
        // silent `?: return` pattern that W0-4 had purged from every other
        // setter. Aligned here so the UI catches are guaranteed to fire on
        // KeyStore failure instead of the flag update being silently dropped.
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        LRRAuthManager.setAllowCleartext(false)
    }

    @Test
    fun getters_returnNullWhenStorageUnavailable() {
        // Reads MUST NOT throw — UI polls getApiKey()/getServerUrl() frequently.
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        assertNull(LRRAuthManager.getServerUrl())
        assertNull(LRRAuthManager.getApiKey())
        assertNull(LRRAuthManager.getServerName())
        assertNull(LRRAuthManager.getApiKeyForProfile(1L))
        assertFalse(AppLockCredentials.hasPattern())
    }

    @Test
    fun clear_toleratesUnavailableStorage() {
        // clear() is safe to call during reauth flows — must not throw even if
        // the backing store was never initialized.
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        SecureCredentialStore.clear() // should not throw
        assertEquals(0L, LRRAuthManager.getActiveProfileId())
    }

    // ── Multi-profile reauth detection (W0-4 part 2) ────────────────

    @Test
    fun markReauthIfProfilesUnprotected_emptyList_doesNothing() {
        // Fresh install: no profiles in Room — should NOT trigger reauth even
        // if storage is broken (there's nothing to recover).
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        LRRAuthManager.markReauthIfProfilesUnprotected(emptyList())
        assertFalse(LRRAuthManager.isNeedsReauthentication())
    }

    @Test
    fun markReauthIfProfilesUnprotected_storageDownWithProfiles_marksReauth() {
        // KeyStore broken AND user has at least one server: keys are unrecoverable.
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        LRRAuthManager.markReauthIfProfilesUnprotected(listOf(1L, 2L))
        assertTrue(LRRAuthManager.isNeedsReauthentication())
    }

    @Test
    fun markReauthIfProfilesUnprotected_allProfilesHaveKeys_doesNotMark() {
        LRRAuthManager.setApiKeyForProfile(1L, "k1")
        LRRAuthManager.setApiKeyForProfile(2L, "k2")
        LRRAuthManager.markReauthIfProfilesUnprotected(listOf(1L, 2L))
        assertFalse(LRRAuthManager.isNeedsReauthentication())
    }

    @Test
    fun markReauthIfProfilesUnprotected_oneMissingKey_marksReauth() {
        // Partial corruption / interrupted migration: one profile lost its key.
        LRRAuthManager.setApiKeyForProfile(1L, "k1")
        // Profile 2 has no key entry
        LRRAuthManager.markReauthIfProfilesUnprotected(listOf(1L, 2L))
        assertTrue(LRRAuthManager.isNeedsReauthentication())
    }

    @Test
    fun markReauthIfProfilesUnprotected_doesNotClearExistingFlag() {
        // Once flagged, additional checks should not unset the flag — only an
        // explicit clear() (after re-auth) should reset it.
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        LRRAuthManager.markReauthIfProfilesUnprotected(listOf(1L))
        assertTrue(LRRAuthManager.isNeedsReauthentication())
        // Restore healthy storage and re-check with all keys present
        SecureCredentialStore.initializeForTesting(
            ctx.getSharedPreferences("lrr_auth_test_2", android.content.Context.MODE_PRIVATE)
        )
        // initializeForTesting() resets the flag — that's the documented "fresh test setup"
        // behavior. Verify subsequent successful checks keep it false.
        LRRAuthManager.setApiKeyForProfile(1L, "restored")
        LRRAuthManager.markReauthIfProfilesUnprotected(listOf(1L))
        assertFalse(LRRAuthManager.isNeedsReauthentication())
    }

    // -- Audit 2026-10-04 C02: deleting the active profile ---------------

    @Test
    fun clearActiveSession_forgetsGlobalCredentials() {
        LRRAuthManager.setServerUrl("http://192.168.1.50:3000")
        LRRAuthManager.setApiKey("old-key")
        LRRAuthManager.setServerName("Old NAS")
        LRRAuthManager.setActiveProfileId(7L)
        LRRAuthManager.setAllowCleartext(true)
        LRRAuthManager.setApiKeyForProfile(8L, "other-profile-key")
        val before = LRRAuthManager.serverConfigVersion

        LRRAuthManager.clearActiveSession()

        assertNull(LRRAuthManager.getServerUrl())
        assertNull(LRRAuthManager.getApiKey())
        assertNull(LRRAuthManager.getServerName())
        assertEquals(0L, LRRAuthManager.getActiveProfileId())
        assertFalse(LRRAuthManager.isConfigured())
        assertTrue(LRRAuthManager.serverConfigVersion > before)
        // Other profiles' keys are not part of the active session.
        assertEquals("other-profile-key", LRRAuthManager.getApiKeyForProfile(8L))
    }

    @Test
    fun clearSessionIfActiveProfileGone_clearsWhenActiveRowIsMissing() {
        LRRAuthManager.setServerUrl("http://192.168.1.50:3000")
        LRRAuthManager.setApiKey("old-key")
        LRRAuthManager.setActiveProfileId(7L)

        assertTrue(LRRAuthManager.clearSessionIfActiveProfileGone(listOf(1L, 2L)))

        assertNull(LRRAuthManager.getApiKey())
        assertNull(LRRAuthManager.getServerUrl())
    }

    @Test
    fun clearSessionIfActiveProfileGone_keepsLiveAndLegacySessions() {
        LRRAuthManager.setServerUrl("http://192.168.1.50:3000")
        LRRAuthManager.setApiKey("key")

        // Legacy single-server setup: no active profile id at all.
        LRRAuthManager.setActiveProfileId(0L)
        assertFalse(LRRAuthManager.clearSessionIfActiveProfileGone(emptyList()))

        // The active profile still exists.
        LRRAuthManager.setActiveProfileId(2L)
        assertFalse(LRRAuthManager.clearSessionIfActiveProfileGone(listOf(1L, 2L)))

        assertEquals("key", LRRAuthManager.getApiKey())
        assertEquals("http://192.168.1.50:3000", LRRAuthManager.getServerUrl())
    }
}
