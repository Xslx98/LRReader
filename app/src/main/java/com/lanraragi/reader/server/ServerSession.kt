package com.lanraragi.reader.server

import androidx.core.content.edit
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.server.CredentialStoreLayout.profileApiKey

/**
 * The active server and the per-profile API keys. The active server is
 * owned by Room (the `IS_ACTIVE` profile) through [ProfileLookupCache];
 * this object only reads it. The keys live in [SecureCredentialStore],
 * whose readiness gate holds readers until the store has opened.
 */
object ServerSession {

    private val store get() = SecureCredentialStore

    /** The process's profile cache, or null while the app is still booting (or in a test without one). */
    private fun cache(): ProfileLookupCache? =
        runCatching { ServiceRegistry.dataModule.profileLookupCache }.getOrNull()

    /**
     * The active server, waiting (bounded) for the profile list on a cold
     * start. Null when no profile is active.
     */
    @JvmStatic
    fun activeServer(): ActiveServer? = cache()?.activeServerBlocking()

    /**
     * Changes whenever the active server changes or its API key is written. Screens compare it with the value they last saw to know
     * that their content came from another server (or credential).
     */
    @JvmStatic
    val serverConfigVersion: Long
        get() = (cache()?.activeRevision ?: 0L) + store.keyRevision.get()

    /** The active server's base URL, e.g. "http://192.168.1.100:3000". */
    @JvmStatic
    fun getServerUrl(): String? = activeServer()?.url

    /** The active server's API key (plaintext, not base64-encoded). */
    @JvmStatic
    fun getApiKey(): String? = activeServer()?.let { getApiKeyForProfile(it.id) }

    /** True when a server profile is active. */
    @JvmStatic
    fun isConfigured(): Boolean = activeServer() != null

    /** ID of the active server profile, 0 if none. */
    @JvmStatic
    fun getActiveProfileId(): Long = activeServer()?.id ?: 0L

    /**
     * @return true if encryption was unavailable during initialize() and the user
     *         should be prompted to re-enter their API key.
     */
    @JvmStatic
    fun isNeedsReauthentication(): Boolean {
        store.awaitInit()
        return store.needsReauthentication
    }

    /**
     * Inspect every known server profile and mark reauthentication required if any
     * profile is missing its API key. Should be called from a background thread once
     * the Room database is ready and the full profile list has been loaded.
     *
     * Two failure modes are detected:
     *
     *   1. The encrypted backing store is unavailable AND the user already has at
     *      least one profile in Room — every key was lost.
     *   2. The backing store is available but at least one profile has no entry under
     *      `api_key_$id` — partial corruption / interrupted migration.
     *
     * Both leave the user in a state where the auth interceptor would silently send
     * requests with no Bearer token; we set the reauthentication flag so MainActivity /
     * ServerListScene surface the dialog and direct the user to ServerListScene.
     */
    @JvmStatic
    fun markReauthIfProfilesUnprotected(profileIds: List<Long>) {
        store.awaitInit()
        if (profileIds.isEmpty()) return
        val prefs = store.prefs
        if (prefs == null) {
            // KeyStore is broken AND profiles exist in Room: keys are unrecoverable.
            store.needsReauthentication = true
            return
        }
        // KeyStore is up but verify each profile has its api_key entry.
        for (id in profileIds) {
            if (!prefs.contains(profileApiKey(id))) {
                store.needsReauthentication = true
                return
            }
        }
        // All profiles accounted for — clear any stale reauth flag.
        store.needsReauthentication = false
    }

    // ── Per-profile API key storage (encrypted, keyed by profile ID) ──────────

    /**
     * Store the API key for a specific server profile in encrypted prefs.
     * Use this instead of storing keys in the Room `SERVER_PROFILES` table.
     */
    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setApiKeyForProfile(profileId: Long, apiKey: String?) {
        val prefs = store.requireSecurePrefs("setApiKeyForProfile")
        val prefKey = profileApiKey(profileId)
        // Store empty string (not remove) so markReauthIfProfilesUnprotected can
        // distinguish "intentionally no key" from "key lost due to KeyStore failure".
        prefs.edit { putString(prefKey, apiKey ?: "") }
        store.profileKeyCache[profileId] = apiKey.orEmpty()
        if (cache()?.activeServer?.value?.id == profileId) store.keyRevision.incrementAndGet()
    }

    /** @return the API key for the given profile, or null if none stored / empty. */
    @JvmStatic
    fun getApiKeyForProfile(profileId: Long): String? {
        // Cache hit: no init gate, no crypto — see SecureCredentialStore.profileKeyCache.
        store.profileKeyCache[profileId]?.let { return it.ifEmpty { null } }
        store.awaitInit()
        // Keystore unavailable: return null WITHOUT caching, so a later
        // recovered store serves real keys instead of a poisoned miss.
        val prefs = store.prefs ?: return null
        val key = prefs.getString(profileApiKey(profileId), null)
        store.profileKeyCache[profileId] = key.orEmpty()
        return key?.ifEmpty { null }
    }

    /** Remove the stored API key for a profile (e.g., when the profile is deleted). */
    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun clearApiKeyForProfile(profileId: Long) {
        val prefs = store.requireSecurePrefs("clearApiKeyForProfile")
        prefs.edit { remove(profileApiKey(profileId)) }
        store.profileKeyCache.remove(profileId)
        if (cache()?.activeServer?.value?.id == profileId) store.keyRevision.incrementAndGet()
    }
}
