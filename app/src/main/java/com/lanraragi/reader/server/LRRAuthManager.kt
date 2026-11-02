package com.lanraragi.reader.server

import androidx.core.content.edit
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_ACTIVE_PROFILE_ID
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_ALLOW_CLEARTEXT
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_API_KEY
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_CONFIGURED_HINT
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_SERVER_NAME
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_SERVER_URL
import com.lanraragi.reader.server.CredentialStoreLayout.profileApiKey

/**
 * The active server session (server URL, API key, server name, active
 * profile id, cleartext opt-in) and the per-profile API keys, stored in
 * [SecureCredentialStore]. This object holds no state of its own; the
 * store's readiness gate holds readers until the store has opened.
 */
object LRRAuthManager {

    private val store get() = SecureCredentialStore

    /**
     * Monotonically increasing counter bumped whenever the active server profile
     * is modified (URL, API key, name, cleartext flag). Observers compare against
     * their last-seen value to detect changes and trigger a refresh.
     */
    @JvmStatic
    val serverConfigVersion: Long
        get() = store.serverConfigVersion.get()

    /** Bump the config version. Call after any active-profile credential write. */
    @JvmStatic
    fun bumpServerConfigVersion() {
        store.serverConfigVersion.incrementAndGet()
    }

    /**
     * @return The configured server base URL, e.g., "http://192.168.1.100:3000"
     */
    @JvmStatic
    fun getServerUrl(): String? {
        store.awaitInit()
        return store.prefs?.getString(KEY_SERVER_URL, null)
    }

    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setServerUrl(url: String) {
        val prefs = store.requireSecurePrefs("setServerUrl")
        // Remove trailing slash
        var cleanUrl = url
        if (cleanUrl.endsWith("/")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.length - 1)
        }
        prefs.edit { putString(KEY_SERVER_URL, cleanUrl) }
        store.plainPrefs?.edit { putBoolean(KEY_CONFIGURED_HINT, cleanUrl.isNotEmpty()) }
    }

    /**
     * @return The API key (plaintext, not base64-encoded)
     */
    @JvmStatic
    fun getApiKey(): String? {
        store.awaitInit()
        return store.prefs?.getString(KEY_API_KEY, null)
    }

    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setApiKey(apiKey: String?) {
        val prefs = store.requireSecurePrefs("setApiKey")
        prefs.edit { putString(KEY_API_KEY, apiKey) }
    }

    /**
     * @return Cached server name from last successful connection
     */
    @JvmStatic
    fun getServerName(): String? {
        store.awaitInit()
        return store.prefs?.getString(KEY_SERVER_NAME, null)
    }

    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setServerName(name: String?) {
        val prefs = store.requireSecurePrefs("setServerName")
        prefs.edit { putString(KEY_SERVER_NAME, name) }
    }

    /**
     * @return true if a server URL has been configured
     */
    @JvmStatic
    fun isConfigured(): Boolean {
        val url = getServerUrl()
        return !url.isNullOrEmpty()
    }

    /** Whether a server URL is set, from the plain mirror; null when unknown. */
    @JvmStatic
    fun configuredHint(): Boolean? {
        val plain = store.fastPlainPrefs() ?: return null
        return if (plain.contains(KEY_CONFIGURED_HINT)) plain.getBoolean(KEY_CONFIGURED_HINT, false) else null
    }

    /**
     * [isConfigured] without waiting for the keystore when the plain mirror
     * is known — for the synchronous launch decision.
     */
    @JvmStatic
    fun isConfiguredFast(): Boolean = configuredHint() ?: isConfigured()

    /**
     * @return ID of the currently active ServerProfile (0 if none)
     */
    @JvmStatic
    fun getActiveProfileId(): Long {
        store.awaitInit()
        return store.activeProfileId
    }

    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setActiveProfileId(id: Long) {
        store.activeProfileId = id
        val prefs = store.requireSecurePrefs("setActiveProfileId")
        prefs.edit { putLong(KEY_ACTIVE_PROFILE_ID, id) }
    }

    /**
     * @return whether the active server profile permits cleartext (HTTP) requests.
     *         When the store is open but the key is absent, defaults to `true`
     *         (existing HTTP profiles never wrote this key explicitly).
     *         When the store is unavailable (KeyStore failure), returns `false` (fail-closed).
     *         ServerListScene calls [setAllowCleartext] on every profile switch.
     *         Read by [LRRCleartextRejectionInterceptor] every request.
     */
    @JvmStatic
    fun getAllowCleartext(): Boolean {
        store.awaitInit()
        return store.prefs?.getBoolean(KEY_ALLOW_CLEARTEXT, true) ?: false
    }

    /**
     * Cache the active profile's `allowCleartext` flag. Called by ServerListScene
     * on every profile switch. Throws [LRRSecureStorageUnavailableException] if
     * the secure backing store is unavailable, consistent with all other setters
     * (W0-4: no silent `?: return` in any credential setter).
     */
    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setAllowCleartext(allow: Boolean) {
        val prefs = store.requireSecurePrefs("setAllowCleartext")
        prefs.edit { putBoolean(KEY_ALLOW_CLEARTEXT, allow) }
    }

    /**
     * Forget the active server session: URL, API key, name, cleartext flag and
     * active profile id. Called when the active profile is deleted. The
     * per-profile key store is untouched. Without this, [LRRAuthInterceptor]'s
     * active-server fallback kept attaching the deleted server's key to its
     * host (audit 2026-10-04 C02).
     */
    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun clearActiveSession() {
        val prefs = store.requireSecurePrefs("clearActiveSession")
        prefs.edit {
            remove(KEY_SERVER_URL)
            remove(KEY_API_KEY)
            remove(KEY_SERVER_NAME)
            remove(KEY_ALLOW_CLEARTEXT)
            putLong(KEY_ACTIVE_PROFILE_ID, 0L)
        }
        store.activeProfileId = 0L
        store.plainPrefs?.edit { putBoolean(KEY_CONFIGURED_HINT, false) }
        bumpServerConfigVersion()
    }

    /**
     * Boot repair: if the stored active profile id names a profile that no
     * longer exists in [existingProfileIds], the active session belongs to a
     * deleted server — clear it. An id of 0 (legacy single-server setups that
     * pre-date profiles) is left alone.
     *
     * @return true when the session was cleared
     */
    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun clearSessionIfActiveProfileGone(existingProfileIds: Collection<Long>): Boolean {
        val active = getActiveProfileId()
        if (active == 0L || active in existingProfileIds) return false
        clearActiveSession()
        return true
    }

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
    }
}
