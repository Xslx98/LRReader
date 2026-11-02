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
 * Unit tests for [ServerSession].
 *
 * Uses Robolectric because [SecureCredentialStore.initialize] creates [EncryptedSharedPreferences]
 * which requires an Android Context. The test Application is a plain [android.app.Application]
 * so the Android KeyStore is unavailable in this environment — [SecureCredentialStore.initialize]
 * will set sPrefs=null. We always recover via [SecureCredentialStore.initializeForTesting] which
 * injects a plain SharedPreferences so that all credential round-trip tests remain meaningful.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ServerSessionTest {

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
        ServerSession.setServerUrl("http://192.168.1.100:3000")
        assertEquals("http://192.168.1.100:3000", ServerSession.getServerUrl())
    }

    @Test
    fun setServerUrl_stripsTrailingSlash() {
        ServerSession.setServerUrl("http://192.168.1.100:3000/")
        assertEquals("http://192.168.1.100:3000", ServerSession.getServerUrl())
    }

    @Test
    fun isConfigured_falseWhenNoUrl() {
        assertFalse(ServerSession.isConfigured())
    }

    @Test
    fun isConfigured_trueAfterSettingUrl() {
        ServerSession.setServerUrl("http://192.168.1.100:3000")
        assertTrue(ServerSession.isConfigured())
    }

    // ── API key ─────────────────────────────────────────────────────

    @Test
    fun setAndGetApiKey_roundtrips() {
        ServerSession.setApiKey("abc123")
        assertEquals("abc123", ServerSession.getApiKey())
    }

    @Test
    fun setApiKey_nullClearsKey() {
        ServerSession.setApiKey("abc123")
        ServerSession.setApiKey(null)
        assertNull(ServerSession.getApiKey())
    }

    // ── Per-profile API key ─────────────────────────────────────────

    @Test
    fun setAndGetApiKeyForProfile_roundtrips() {
        ServerSession.setApiKeyForProfile(42L, "profile-key")
        assertEquals("profile-key", ServerSession.getApiKeyForProfile(42L))
    }

    @Test
    fun clearApiKeyForProfile_removesOnlyThatProfilesKey() {
        ServerSession.setApiKeyForProfile(41L, "keep-me")
        ServerSession.setApiKeyForProfile(42L, "profile-key")
        ServerSession.clearApiKeyForProfile(42L)
        assertNull(ServerSession.getApiKeyForProfile(42L))
        assertEquals("keep-me", ServerSession.getApiKeyForProfile(41L))
    }

    @Test
    fun setApiKeyForProfile_emptyString_readsAsNoKey() {
        ServerSession.setApiKeyForProfile(5L, "some_key")
        ServerSession.setApiKeyForProfile(5L, "")
        assertNull(ServerSession.getApiKeyForProfile(5L))
    }

    @Test
    fun profileKeysAreIsolated() {
        ServerSession.setApiKeyForProfile(1L, "key-1")
        ServerSession.setApiKeyForProfile(2L, "key-2")
        assertEquals("key-1", ServerSession.getApiKeyForProfile(1L))
        assertEquals("key-2", ServerSession.getApiKeyForProfile(2L))
    }

    // ── Active profile ID ───────────────────────────────────────────

    @Test
    fun setAndGetActiveProfileId_roundtrips() {
        ServerSession.setActiveProfileId(99L)
        assertEquals(99L, ServerSession.getActiveProfileId())
    }

    @Test
    fun isNeedsReauthentication_falseAfterInitializeForTesting() {
        assertFalse("Reauth flag should be false when KeyStore is available",
            ServerSession.isNeedsReauthentication())
    }

    @Test
    fun getServerUrl_returnsNullWhenNotSet() {
        assertNull(ServerSession.getServerUrl())
    }

    @Test
    fun clear_removesAllCredentials() {
        ServerSession.setServerUrl("http://test.local")
        ServerSession.setApiKey("key")
        ServerSession.setActiveProfileId(5L)
        AppLockCredentials.setPattern("pin")
        SecureCredentialStore.clear()
        assertNull(ServerSession.getServerUrl())
        assertNull(ServerSession.getApiKey())
        assertEquals(0L, ServerSession.getActiveProfileId())
        assertFalse(AppLockCredentials.hasPattern())
    }

    // ── Allow cleartext flag ────────────────────────────────────────

    @Test
    fun allowCleartext_defaultsToTrueWhenPrefsAvailable() {
        // Default value (no key in prefs, sPrefs available) — existing HTTP profiles
        // never wrote this key explicitly, so default must be true for compatibility
        assertTrue("default should be true when sPrefs available", ServerSession.getAllowCleartext())
    }

    @Test
    fun setAllowCleartext_roundTripsThroughPrefs() {
        ServerSession.setAllowCleartext(false)
        assertFalse("after setAllowCleartext(false)", ServerSession.getAllowCleartext())

        ServerSession.setAllowCleartext(true)
        assertTrue("after setAllowCleartext(true)", ServerSession.getAllowCleartext())
    }

    @Test
    fun allowCleartext_returnsFalseWhenStorageUnavailable() {
        // When sPrefs is null (KeyStore broken), getAllowCleartext() must fail-closed
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        assertFalse(
            "getAllowCleartext must return false when secure storage is unavailable",
            ServerSession.getAllowCleartext()
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
        ServerSession.setServerUrl("http://test.local")
    }

    @Test(expected = LRRSecureStorageUnavailableException::class)
    fun setApiKey_throwsWhenStorageUnavailable() {
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        ServerSession.setApiKey("secret")
    }

    @Test(expected = LRRSecureStorageUnavailableException::class)
    fun setServerName_throwsWhenStorageUnavailable() {
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        ServerSession.setServerName("LRR")
    }

    @Test(expected = LRRSecureStorageUnavailableException::class)
    fun setActiveProfileId_throwsWhenStorageUnavailable() {
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        ServerSession.setActiveProfileId(7L)
    }

    @Test(expected = LRRSecureStorageUnavailableException::class)
    fun setApiKeyForProfile_throwsWhenStorageUnavailable() {
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        ServerSession.setApiKeyForProfile(1L, "key")
    }

    @Test(expected = LRRSecureStorageUnavailableException::class)
    fun clearApiKeyForProfile_throwsWhenStorageUnavailable() {
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        ServerSession.clearApiKeyForProfile(1L)
    }

    @Test(expected = LRRSecureStorageUnavailableException::class)
    fun setAllowCleartext_throwsWhenStorageUnavailable() {
        // Added during W0-3/W0-4 merge: setAllowCleartext originally used the
        // silent `?: return` pattern that W0-4 had purged from every other
        // setter. Aligned here so the UI catches are guaranteed to fire on
        // KeyStore failure instead of the flag update being silently dropped.
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        ServerSession.setAllowCleartext(false)
    }

    @Test
    fun getters_returnNullWhenStorageUnavailable() {
        // Reads MUST NOT throw — UI polls getApiKey()/getServerUrl() frequently.
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        assertNull(ServerSession.getServerUrl())
        assertNull(ServerSession.getApiKey())
        assertNull(ServerSession.getServerName())
        assertNull(ServerSession.getApiKeyForProfile(1L))
        assertFalse(AppLockCredentials.hasPattern())
    }

    @Test
    fun clear_toleratesUnavailableStorage() {
        // clear() is safe to call during reauth flows — must not throw even if
        // the backing store was never initialized.
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        SecureCredentialStore.clear() // should not throw
        assertEquals(0L, ServerSession.getActiveProfileId())
    }

    // ── Multi-profile reauth detection (W0-4 part 2) ────────────────

    @Test
    fun markReauthIfProfilesUnprotected_emptyList_doesNothing() {
        // Fresh install: no profiles in Room — should NOT trigger reauth even
        // if storage is broken (there's nothing to recover).
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        ServerSession.markReauthIfProfilesUnprotected(emptyList())
        assertFalse(ServerSession.isNeedsReauthentication())
    }

    @Test
    fun markReauthIfProfilesUnprotected_storageDownWithProfiles_marksReauth() {
        // KeyStore broken AND user has at least one server: keys are unrecoverable.
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        ServerSession.markReauthIfProfilesUnprotected(listOf(1L, 2L))
        assertTrue(ServerSession.isNeedsReauthentication())
    }

    @Test
    fun markReauthIfProfilesUnprotected_allProfilesHaveKeys_doesNotMark() {
        ServerSession.setApiKeyForProfile(1L, "k1")
        ServerSession.setApiKeyForProfile(2L, "k2")
        ServerSession.markReauthIfProfilesUnprotected(listOf(1L, 2L))
        assertFalse(ServerSession.isNeedsReauthentication())
    }

    @Test
    fun markReauthIfProfilesUnprotected_oneMissingKey_marksReauth() {
        // Partial corruption / interrupted migration: one profile lost its key.
        ServerSession.setApiKeyForProfile(1L, "k1")
        // Profile 2 has no key entry
        ServerSession.markReauthIfProfilesUnprotected(listOf(1L, 2L))
        assertTrue(ServerSession.isNeedsReauthentication())
    }

    @Test
    fun markReauthIfProfilesUnprotected_doesNotClearExistingFlag() {
        // Once flagged, additional checks should not unset the flag — only an
        // explicit clear() (after re-auth) should reset it.
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        ServerSession.markReauthIfProfilesUnprotected(listOf(1L))
        assertTrue(ServerSession.isNeedsReauthentication())
        // Restore healthy storage and re-check with all keys present
        SecureCredentialStore.initializeForTesting(
            ctx.getSharedPreferences("lrr_auth_test_2", android.content.Context.MODE_PRIVATE)
        )
        // initializeForTesting() resets the flag — that's the documented "fresh test setup"
        // behavior. Verify subsequent successful checks keep it false.
        ServerSession.setApiKeyForProfile(1L, "restored")
        ServerSession.markReauthIfProfilesUnprotected(listOf(1L))
        assertFalse(ServerSession.isNeedsReauthentication())
    }

    // -- Audit 2026-10-04 C02: deleting the active profile ---------------

    @Test
    fun clearActiveSession_forgetsGlobalCredentials() {
        ServerSession.setServerUrl("http://192.168.1.50:3000")
        ServerSession.setApiKey("old-key")
        ServerSession.setServerName("Old NAS")
        ServerSession.setActiveProfileId(7L)
        ServerSession.setAllowCleartext(true)
        ServerSession.setApiKeyForProfile(8L, "other-profile-key")
        val before = ServerSession.serverConfigVersion

        ServerSession.clearActiveSession()

        assertNull(ServerSession.getServerUrl())
        assertNull(ServerSession.getApiKey())
        assertNull(ServerSession.getServerName())
        assertEquals(0L, ServerSession.getActiveProfileId())
        assertFalse(ServerSession.isConfigured())
        assertTrue(ServerSession.serverConfigVersion > before)
        // Other profiles' keys are not part of the active session.
        assertEquals("other-profile-key", ServerSession.getApiKeyForProfile(8L))
    }

    @Test
    fun clearSessionIfActiveProfileGone_clearsWhenActiveRowIsMissing() {
        ServerSession.setServerUrl("http://192.168.1.50:3000")
        ServerSession.setApiKey("old-key")
        ServerSession.setActiveProfileId(7L)

        assertTrue(ServerSession.clearSessionIfActiveProfileGone(listOf(1L, 2L)))

        assertNull(ServerSession.getApiKey())
        assertNull(ServerSession.getServerUrl())
    }

    @Test
    fun clearSessionIfActiveProfileGone_keepsLiveAndLegacySessions() {
        ServerSession.setServerUrl("http://192.168.1.50:3000")
        ServerSession.setApiKey("key")

        // Legacy single-server setup: no active profile id at all.
        ServerSession.setActiveProfileId(0L)
        assertFalse(ServerSession.clearSessionIfActiveProfileGone(emptyList()))

        // The active profile still exists.
        ServerSession.setActiveProfileId(2L)
        assertFalse(ServerSession.clearSessionIfActiveProfileGone(listOf(1L, 2L)))

        assertEquals("key", ServerSession.getApiKey())
        assertEquals("http://192.168.1.50:3000", ServerSession.getServerUrl())
    }
}
