package com.lanraragi.reader.server

import android.util.Log
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRUrlHelper
import com.lanraragi.reader.client.api.data.LRRServerInfo
import com.lanraragi.reader.dao.ServerProfile
import com.lanraragi.reader.appwidget.ContinueReadingWidget
import com.lanraragi.reader.reading.ArchiveProgressOutbox
import com.lanraragi.reader.reading.LocalReadingProgress
import com.lanraragi.reader.shortcuts.ContinueReadingShortcut
import com.lanraragi.reader.tankoubon.TankCoverChoiceStore
import com.lanraragi.reader.tankoubon.TankRecentStore
import com.lanraragi.reader.tankoubon.TankTagOutbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Server profile changes the server screens ask for: onboarding connect,
 * add, edit, switch and delete. The connecting ones test the server first,
 * then commit in a fixed order (Room row, keystore key, active flag, live
 * session last), so an error at any step leaves the previous server in
 * charge.
 *
 * Every operation runs on the process scope: a profile change the user
 * started finishes even when the screen that asked for it closes, so a
 * commit is never cut between its steps. The screen only awaits the
 * outcome; cancelling that wait never cancels the change.
 *
 * @param scope the long-lived scope operations run on; null = the app's IO
 *   scope, resolved at the first call
 */
class ServerProfileService(private val scope: CoroutineScope? = null) {

    /** How a test-then-commit operation ended. */
    sealed interface Outcome<out T> {
        data class Done<T>(val value: T) : Outcome<T>

        /** The server test or the commit failed; nothing live was switched. */
        data class Failed(val error: Exception) : Outcome<Nothing>

        /** The server's certificate is not trusted yet: ask the user, then repeat. */
        data class Untrusted(val certificate: ServerCertificate) : Outcome<Nothing>

        /** The secure store is unavailable; the commit was rolled back or never started. */
        data object SecureStorageUnavailable : Outcome<Nothing>
    }

    /** The onboarding connect committed: [resolvedUrl] is now the active server. */
    data class Onboarded(val resolvedUrl: String, val info: LRRServerInfo)

    /**
     * Onboarding connect: tests [rawInput] (scheme detection, HTTPS->HTTP
     * fallback and the cleartext LAN gate), then creates or updates the
     * profile for the resolved URL and makes it the active server.
     *
     * NET-7: the candidate key rides the test request itself (explicit
     * Bearer header on a stripped test client); no global auth state is
     * written before the test succeeds.
     */
    suspend fun connectAndActivate(rawInput: String, apiKey: String?, allowCleartext: Boolean): Outcome<Onboarded> =
        onProcessScope {
            val candidateKey = apiKey?.ifEmpty { null }
            val testClient = LRRUrlHelper.buildTestClient(ServiceRegistry.networkModule.okHttpClient)
            when (val r = LRRUrlHelper.connectWithFallback(testClient, rawInput, candidateKey, allowCleartext)) {
                is LRRUrlHelper.ConnectResult.Failure -> Outcome.Failed(r.error)
                is LRRUrlHelper.ConnectResult.UntrustedCertificate -> Outcome.Untrusted(r.certificate)
                is LRRUrlHelper.ConnectResult.Success -> commitOnboarding(r.resolvedUrl, r.info, candidateKey, allowCleartext)
            }
        }

    private suspend fun commitOnboarding(
        resolvedUrl: String,
        info: LRRServerInfo,
        apiKey: String?,
        allowCleartext: Boolean,
    ): Outcome<Onboarded> {
        val profileRepository = ServiceRegistry.dataModule.profileRepository
        try {
            // The persisted cleartext flag is the user's consent (as on the
            // add/edit paths, audit C27 ruling R11); LAN hosts need none —
            // the interceptor admits them like the connect gate does.
            // Room first: create or update the profile row. A failure here is
            // reported with the live global auth still on the previous server,
            // so a persistence error never silently switches traffic to an
            // unpersisted candidate. The active flag moves only after the key
            // is stored (below), in one atomic UPDATE: never zero active
            // profiles, never an active one without its key.
            val existing = profileRepository.findByUrl(resolvedUrl)
            val inserted: ServerProfile?
            val profileId = if (existing != null) {
                inserted = null
                profileRepository.update(
                    ServerProfile(existing.id, info.name ?: existing.name, resolvedUrl, existing.isActive, allowCleartext)
                )
                existing.id
            } else {
                val row = ServerProfile(0, info.name ?: "LANraragi", resolvedUrl, false, allowCleartext)
                val id = profileRepository.insert(row)
                inserted = row.copy(id = id)
                id
            }

            // Keystore per-profile key, then activation, then live global
            // auth LAST. (API key rides EncryptedSharedPreferences, not
            // Room.) bumpServerConfigVersion drives GalleryListScene's
            // auto-refresh, so re-onboarding refreshes the list like add/edit.
            try {
                LRRAuthManager.setApiKeyForProfile(profileId, apiKey)
            } catch (e: LRRSecureStorageUnavailableException) {
                inserted?.let { profileRepository.delete(it) }
                throw e
            }
            profileRepository.activateExclusive(profileId)
            LRRAuthManager.setActiveProfileId(profileId)
            LRRAuthManager.setServerUrl(resolvedUrl)
            LRRAuthManager.setApiKey(apiKey)
            LRRAuthManager.setServerName(info.name)
            LRRAuthManager.setAllowCleartext(allowCleartext)
            LRRAuthManager.bumpServerConfigVersion()
        } catch (e: LRRSecureStorageUnavailableException) {
            Log.e(TAG, "Secure storage unavailable on connect success", e)
            return Outcome.SecureStorageUnavailable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist profile on connect success", e)
            return Outcome.Failed(e)
        }
        return Outcome.Done(Onboarded(resolvedUrl, info))
    }

    /** How a server switch ended. */
    enum class SwitchResult { SWITCHED, SECURE_STORAGE_UNAVAILABLE, FAILED }

    /**
     * Makes [profile] the active server: one atomic Room UPDATE, then the
     * live session. The previous server's caches are dropped afterwards, in
     * the background, so its content cannot show up under the new one.
     */
    suspend fun activate(profile: ServerProfile): SwitchResult = onProcessScope { switchTo(profile) }

    private suspend fun switchTo(profile: ServerProfile): SwitchResult {
        try {
            // Single atomic statement: flips IS_ACTIVE for the chosen profile and
            // clears it for all others without a window where none is active. Other
            // columns (name/url/allowCleartext) are untouched and thus preserved.
            ServiceRegistry.dataModule.profileRepository.activateExclusive(profile.id)
            // Every secure-store access is an AndroidKeyStore AES-GCM call (a
            // binder round trip into keystore2), so the session switch runs
            // here, off main (audit 2026-10-13 STAB-10).
            applySessionFor(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LRRSecureStorageUnavailableException) {
            Log.e(TAG, "Secure storage unavailable while switching profile", e)
            return SwitchResult.SECURE_STORAGE_UNAVAILABLE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to activate profile", e)
            return SwitchResult.FAILED
        }
        clearCachesAfterSwitch()
        return SwitchResult.SWITCHED
    }

    /**
     * Drops the previous server's caches. Not awaited by the switch: the disk
     * caches delete their files and may wait for in-flight readers.
     */
    private fun clearCachesAfterSwitch() {
        processScope().launch {
            try {
                ServiceRegistry.clearAllCaches()
            } catch (e: Exception) {
                Log.e(TAG, "Cache clear after profile switch failed", e)
            }
        }
    }

    /** Points the auth session at [profile]. Blocking keystore work: call off main. */
    private fun applySessionFor(profile: ServerProfile) {
        LRRAuthManager.setServerUrl(profile.url)
        LRRAuthManager.setApiKey(LRRAuthManager.getApiKeyForProfile(profile.id))
        LRRAuthManager.setServerName(profile.name)
        LRRAuthManager.setActiveProfileId(profile.id)
        LRRAuthManager.setAllowCleartext(profile.allowCleartext)
        LRRAuthManager.bumpServerConfigVersion()
    }

    /**
     * Deletes [profile]. Its keys go first, on the caller's thread: the
     * active profile's URL and key are also mirrored into the global session
     * that the auth interceptor falls back to, so that is cleared before the
     * row goes, or the deleted server keeps receiving the key. Then the row
     * and everything kept for that server are purged on the process scope.
     *
     * @return the running purge (it never fails; errors are logged), or null
     *   when the secure store is unavailable and nothing was deleted
     */
    fun delete(profile: ServerProfile): Deferred<Unit>? {
        val wasActive = profile.isActive || LRRAuthManager.getActiveProfileId() == profile.id
        try {
            LRRAuthManager.clearApiKeyForProfile(profile.id)
            if (wasActive) LRRAuthManager.clearActiveSession()
        } catch (e: LRRSecureStorageUnavailableException) {
            Log.e(TAG, "Secure storage unavailable while deleting a profile", e)
            return null
        }
        return processScope().async { purge(profile) }
    }

    private suspend fun purge(profile: ServerProfile) {
        try {
            val data = ServiceRegistry.dataModule
            data.profileRepository.delete(profile)
            // Cascade (triage decision, issue #12): a deleted profile's
            // search history must not linger.
            data.searchHistoryRepository.deleteAllForProfile(profile.id)
            // Likewise its reading history, local favourites and stats
            // aggregates (audit 2026-10-04 C22). Downloads stay listed.
            data.historyRepository.purgeDeletedProfile(profile.id)
            purgeServerState(profile)
            forgetPinIfUnused(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete profile", e)
        }
        // The deleted server's thumbnails and details must not linger on
        // disk or show up under whatever is picked next (audit SEC-16).
        try {
            ServiceRegistry.clearAllCaches()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Cache clear after deleting a profile failed", e)
        }
        // A continue-reading shortcut pointing at the deleted profile is
        // now a dead end — drop it (issue #16). The widget re-renders from
        // the most recent surviving profile's history instead (issue #9).
        ContinueReadingShortcut.removeIfProfileSafely(profile.id)
        ContinueReadingWidget.refreshSafely()
    }

    /**
     * Per-server state outside Room that a deleted profile leaves behind
     * (audit SEC-16): capability facts, pending progress PUTs, pending tank
     * tag retries, tank cover choices, local reading positions (audit
     * 2026-10-06 C37). Base URLs are stored without a trailing slash.
     */
    private fun purgeServerState(profile: ServerProfile) {
        val baseUrl = profile.url.removeSuffix("/")
        ServerCapabilityCache.forget(baseUrl)
        ArchiveProgressOutbox.dropServer(baseUrl)
        TankTagOutbox.dropServer(baseUrl)
        TankCoverChoiceStore.default.removeProfile(profile.id)
        TankRecentStore.default.removeProfile(profile.id)
        LocalReadingProgress.removeProfile(ServiceRegistry.appModule.getContext(), profile.id)
    }

    /**
     * A deleted server's pinned certificate goes with it, unless another
     * server uses the same host:port (audit C27 phase 2).
     */
    private suspend fun forgetPinIfUnused(profile: ServerProfile) {
        val url = profile.url.toHttpUrlOrNull() ?: return
        val stillUsed = ServiceRegistry.dataModule.profileRepository.getAllProfiles().any { other ->
            other.url.toHttpUrlOrNull()
                ?.let { it.host == url.host && it.port == url.port } == true
        }
        if (!stillUsed) ServerTrust.forgetPin(url.host, url.port)
    }

    /** An edited profile was saved as [updated]; [usedHttpFallback] = HTTPS failed and HTTP answered. */
    data class Edited(val updated: ServerProfile, val usedHttpFallback: Boolean)

    /**
     * Tests the edited address of [profile] and saves the edit: keystore key
     * first, then Room, then (for the active profile) the live session.
     *
     * NET-7: the probe is self-contained (explicit Bearer header on a
     * stripped test client); no global auth state is touched by it. It is
     * gated on the dialog's current cleartext consent, not the stale stored
     * flag, so a just-granted plain-HTTP opt-in is honoured and a revoked one
     * is enforced.
     */
    suspend fun connectAndSaveEdit(
        profile: ServerProfile,
        newName: String,
        newUrl: String,
        newKey: String,
        allowCleartext: Boolean,
    ): Outcome<Edited> = onProcessScope {
        val testClient = LRRUrlHelper.buildTestClient(ServiceRegistry.networkModule.okHttpClient)
        when (val r = LRRUrlHelper.connectWithFallback(testClient, newUrl, newKey.ifEmpty { null }, allowCleartext)) {
            is LRRUrlHelper.ConnectResult.Failure -> Outcome.Failed(r.error)
            is LRRUrlHelper.ConnectResult.UntrustedCertificate -> Outcome.Untrusted(r.certificate)
            is LRRUrlHelper.ConnectResult.Success ->
                saveEdit(profile, ServerProfile(profile.id, newName, r.resolvedUrl, profile.isActive, allowCleartext), newKey)
                    .map { Edited(it, r.usedHttpFallback) }
        }
    }

    /**
     * Saves [updated] (the dialog's name, the resolved URL and the user's
     * cleartext consent, audit C27 ruling R11) with [newKey].
     */
    private suspend fun saveEdit(profile: ServerProfile, updated: ServerProfile, newKey: String): Outcome<ServerProfile> {
        val profileRepository = ServiceRegistry.dataModule.profileRepository
        try {
            try {
                // Fragile keystore per-profile key first: if the keystore is
                // unavailable this throws before any durable write, so the
                // edit aborts with Room and live auth untouched.
                LRRAuthManager.setApiKeyForProfile(profile.id, newKey.ifEmpty { null })
            } catch (e: LRRSecureStorageUnavailableException) {
                Log.e(TAG, "Secure storage unavailable while saving an edited profile", e)
                return Outcome.SecureStorageUnavailable
            }
            // Durable Room write next. A failure is reported (Failed) with the
            // live global auth still pointing at the old server — no silent switch.
            profileRepository.update(updated)
            // Live global-auth switch LAST, only once Room has committed.
            try {
                if (profile.isActive) {
                    LRRAuthManager.setServerUrl(updated.url)
                    LRRAuthManager.setApiKey(newKey.ifEmpty { null })
                    LRRAuthManager.setServerName(updated.name)
                    LRRAuthManager.setAllowCleartext(updated.allowCleartext)
                    LRRAuthManager.bumpServerConfigVersion()
                }
                LRRAuthManager.markReauthIfProfilesUnprotected(profileRepository.getAllProfiles().map { it.id })
            } catch (e: LRRSecureStorageUnavailableException) {
                Log.e(TAG, "Secure storage unavailable while switching the edited profile", e)
                return Outcome.SecureStorageUnavailable
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The keystore writes already committed but persistence failed
            // (e.g. a Room write error). Reported so the edit dialog re-enables
            // instead of soft-locking with the Save button stuck disabled.
            Log.e(TAG, "Failed to save edited profile", e)
            return Outcome.Failed(e)
        }
        return Outcome.Done(updated)
    }

    /** A new profile was added as [profile] and is now the active server. */
    data class Added(
        val profile: ServerProfile,
        val info: LRRServerInfo,
        val resolvedUrl: String,
        val usedHttpFallback: Boolean,
    )

    /**
     * Tests a new server, persists it and makes it the active one. NET-7:
     * the probe is self-contained, no global auth state is touched by it.
     */
    suspend fun connectAndAdd(
        name: String,
        normalizedUrl: String,
        apiKey: String?,
        allowCleartext: Boolean,
    ): Outcome<Added> = onProcessScope {
        val finalKey = apiKey?.ifEmpty { null }
        val testClient = LRRUrlHelper.buildTestClient(ServiceRegistry.networkModule.okHttpClient)
        when (val r = LRRUrlHelper.connectWithFallback(testClient, normalizedUrl, finalKey, allowCleartext)) {
            is LRRUrlHelper.ConnectResult.Failure -> Outcome.Failed(r.error)
            is LRRUrlHelper.ConnectResult.UntrustedCertificate -> Outcome.Untrusted(r.certificate)
            is LRRUrlHelper.ConnectResult.Success ->
                // The persisted cleartext flag is the user's consent (audit
                // C27, ruling R11), not the resolved scheme; LAN hosts need none.
                add(ServerProfile(0, name, r.resolvedUrl, false, allowCleartext), finalKey)
                    .map { Added(it, r.info, r.resolvedUrl, r.usedHttpFallback) }
        }
    }

    /** Inserts [row] (inactive), stores its key, activates it; returns the active profile. */
    private suspend fun add(row: ServerProfile, finalKey: String?): Outcome<ServerProfile> {
        val profileRepository = ServiceRegistry.dataModule.profileRepository
        val newId = try {
            // Row first, INACTIVE: a failure is reported (Failed) with the old
            // active profile and the live global auth untouched.
            val id = profileRepository.insert(row)
            // Then its key; only a profile whose key is stored becomes
            // active (one atomic UPDATE), else the row is rolled back —
            // never an active profile without its key.
            try {
                LRRAuthManager.setApiKeyForProfile(id, finalKey)
            } catch (e: LRRSecureStorageUnavailableException) {
                Log.e(TAG, "Secure storage unavailable while adding a profile", e)
                profileRepository.delete(row.copy(id = id))
                return Outcome.SecureStorageUnavailable
            }
            profileRepository.activateExclusive(id)
            // Live global auth LAST.
            try {
                LRRAuthManager.setActiveProfileId(id)
                LRRAuthManager.setServerUrl(row.url)
                LRRAuthManager.setApiKey(finalKey)
                LRRAuthManager.setServerName(row.name)
                LRRAuthManager.setAllowCleartext(row.allowCleartext)
                LRRAuthManager.bumpServerConfigVersion()
            } catch (e: LRRSecureStorageUnavailableException) {
                Log.e(TAG, "Secure storage unavailable while switching to the added profile", e)
                return Outcome.SecureStorageUnavailable
            }
            id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Persistence failed after a successful probe. Reported so the add
            // dialog re-enables instead of soft-locking with no feedback; the
            // live global auth was not switched (it runs only after the Room
            // insert succeeds).
            Log.e(TAG, "Failed to add profile", e)
            return Outcome.Failed(e)
        }
        return Outcome.Done(row.copy(id = newId, isActive = true))
    }

    /** [transform]s a [Outcome.Done] payload; any other outcome passes through. */
    private inline fun <A, B> Outcome<A>.map(transform: (A) -> B): Outcome<B> = when (this) {
        is Outcome.Done -> Outcome.Done(transform(value))
        is Outcome.Failed -> this
        is Outcome.Untrusted -> this
        Outcome.SecureStorageUnavailable -> Outcome.SecureStorageUnavailable
    }

    private fun processScope(): CoroutineScope = scope ?: ServiceRegistry.coroutineModule.ioScope

    @Suppress("RedundantSuspendModifier") // detektMain misses kotlinx.coroutines metadata: await() suspends
    private suspend fun <T> onProcessScope(block: suspend CoroutineScope.() -> T): T =
        processScope().async(block = block).await()

    companion object {
        private const val TAG = "ServerProfileService"

        val default: ServerProfileService by lazy { ServerProfileService() }
    }
}
