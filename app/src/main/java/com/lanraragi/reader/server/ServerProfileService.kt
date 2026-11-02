package com.lanraragi.reader.server

import android.util.Log
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRUrlHelper
import com.lanraragi.reader.client.api.data.LRRServerInfo
import com.lanraragi.reader.dao.ServerProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

/**
 * Server profile changes the server screens ask for. Each one tests the
 * server first where it needs to, then commits in a fixed order (Room row,
 * keystore key, active flag, live session last), so an error at any step
 * leaves the previous server in charge.
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

    private suspend fun <T> onProcessScope(block: suspend CoroutineScope.() -> T): T =
        (scope ?: ServiceRegistry.coroutineModule.ioScope).async(block = block).await()

    companion object {
        private const val TAG = "ServerProfileService"

        val default: ServerProfileService by lazy { ServerProfileService() }
    }
}
