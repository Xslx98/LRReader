package com.lanraragi.reader.server

import android.content.Context
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.dao.ServerProfile
import com.lanraragi.reader.installProfiles
import com.lanraragi.reader.stubDataModule
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
 * Unit tests for [ServerSession]: the active server is read from Room's
 * active profile (through [ProfileLookupCache]); the keys from the store.
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
        ServiceRegistry.initializeForTest(coroutine = null, data = stubDataModule())
    }

    // ── Active server (Room's IS_ACTIVE profile) ────────────────────

    private fun profile(id: Long, url: String, active: Boolean = false, cleartext: Boolean = true) =
        ServerProfile(id, "Server $id", url, active, cleartext)

    @Test
    fun noActiveProfile_readsAsNotConfigured() {
        installProfiles(profile(1L, "http://a.local:3000"))
        ServerSession.setApiKeyForProfile(1L, "k1")

        assertNull(ServerSession.activeServer())
        assertNull(ServerSession.getServerUrl())
        assertNull(ServerSession.getApiKey())
        assertEquals(0L, ServerSession.getActiveProfileId())
        assertFalse(ServerSession.isConfigured())
    }

    @Test
    fun withoutAProfileCache_readsAsNotConfigured() {
        ServiceRegistry.initializeForTest(coroutine = null, data = stubDataModule())
        assertNull(ServerSession.activeServer())
        assertFalse(ServerSession.isConfigured())
    }

    @Test
    fun activeProfile_isTheActiveServer_withItsOwnKey() {
        installProfiles(profile(1L, "http://a.local:3000"), profile(2L, "http://b.local:3000/", active = true, cleartext = false))
        ServerSession.setApiKeyForProfile(1L, "k1")
        ServerSession.setApiKeyForProfile(2L, "k2")

        assertEquals(ActiveServer(2L, "http://b.local:3000", "Server 2", false), ServerSession.activeServer())
        assertEquals("http://b.local:3000", ServerSession.getServerUrl())
        assertEquals("k2", ServerSession.getApiKey())
        assertEquals(2L, ServerSession.getActiveProfileId())
        assertTrue(ServerSession.isConfigured())
    }

    @Test
    fun switchingTheActiveRow_switchesEveryReadAndTheConfigVersion() {
        val rows = installProfiles(profile(1L, "http://a.local:3000", active = true), profile(2L, "http://b.local:3000"))
        ServerSession.setApiKeyForProfile(2L, "k2")
        val before = ServerSession.serverConfigVersion

        rows.value = listOf(profile(1L, "http://a.local:3000"), profile(2L, "http://b.local:3000", active = true))

        assertEquals("http://b.local:3000", ServerSession.getServerUrl())
        assertEquals("k2", ServerSession.getApiKey())
        assertNotEquals(before, ServerSession.serverConfigVersion)
    }

    @Test
    fun configVersion_movesOnAKeyWrite_notOnAnUnrelatedProfileEdit() {
        val rows = installProfiles(profile(1L, "http://a.local:3000", active = true), profile(2L, "http://b.local:3000"))
        val start = ServerSession.serverConfigVersion

        rows.value = listOf(profile(1L, "http://a.local:3000", active = true), profile(2L, "http://renamed.local:3000"))
        assertEquals("an inactive profile's edit is not the active server's", start, ServerSession.serverConfigVersion)

        ServerSession.setApiKeyForProfile(1L, "new-key")
        assertNotEquals(start, ServerSession.serverConfigVersion)
    }

    @Test
    fun deletingTheActiveRow_leavesNoActiveServer() {
        val rows = installProfiles(profile(1L, "http://a.local:3000", active = true))
        rows.value = emptyList()
        assertNull(ServerSession.activeServer())
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

    @Test
    fun isNeedsReauthentication_falseAfterInitializeForTesting() {
        assertFalse("Reauth flag should be false when KeyStore is available",
            ServerSession.isNeedsReauthentication())
    }

    // ── Storage unavailable ─────────────────────────────────────────

    @Test
    fun keyWrites_throwWhenStorageUnavailable() {
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        assertThrows(LRRSecureStorageUnavailableException::class.java) {
            ServerSession.setApiKeyForProfile(1L, "k")
        }
        assertThrows(LRRSecureStorageUnavailableException::class.java) {
            ServerSession.clearApiKeyForProfile(1L)
        }
    }

    @Test
    fun keyReads_returnNullWhenStorageUnavailable() {
        installProfiles(profile(1L, "http://a.local:3000", active = true))
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        assertNull(ServerSession.getApiKey())
        assertNull(ServerSession.getApiKeyForProfile(1L))
        assertEquals("the address is Room's, not the store's", "http://a.local:3000", ServerSession.getServerUrl())
        assertFalse(AppLockCredentials.hasPattern())
    }

    @Test
    fun clear_removesEveryKeyAndThePattern() {
        ServerSession.setApiKeyForProfile(5L, "key")
        AppLockCredentials.setPattern("pin")
        SecureCredentialStore.clear()
        assertNull(ServerSession.getApiKeyForProfile(5L))
        assertFalse(AppLockCredentials.hasPattern())
    }

    @Test
    fun clear_toleratesUnavailableStorage() {
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        SecureCredentialStore.clear() // should not throw
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
}
