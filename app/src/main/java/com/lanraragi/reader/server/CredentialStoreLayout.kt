package com.lanraragi.reader.server

import android.content.SharedPreferences

/**
 * The on-disk layout of the credential store: SharedPreferences file names,
 * their keys and the AndroidKeyStore aliases, for the server session
 * ([ServerSession]), the app lock (`security.AppLockCredentials`) and the
 * store itself ([SecureCredentialStore]). Every name here is read back by
 * installed versions of the app: changing one loses the user's API keys or
 * app lock on update. Kept in one place so that stays visible.
 */
internal object CredentialStoreLayout {

    // ── Files and keystore aliases ──────────────────────────────────────

    /** Legacy EncryptedSharedPreferences file, unreadable now (see SecureCredentialStore.abandonLegacyStore). */
    const val PREF_NAME = "lrr_auth_encrypted"

    /** androidx.security's MasterKey.DEFAULT_MASTER_KEY_ALIAS, the legacy store's key. */
    const val LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"

    /** The [KeystoreSecurePrefs] file holding every secret below. */
    const val SECURE_PREF_NAME = "lrr_auth_secure"
    const val KEYSTORE_ALIAS_SECURE_PREFS = "lrr_secure_prefs_key"

    /** Plain prefs: flags, mirrors and lockout state that must survive KeyStore failures. */
    const val PLAIN_PREF_NAME = "lrr_auth_plain"

    /** KeyStore-bound AES-GCM wrapping of the pattern hash (fingerprint unlock). */
    const val KEYSTORE_ALIAS_PATTERN = "lrr_pattern_key"

    // ── Plain prefs: the store's own flags ──────────────────────────────

    /** The legacy store was copied (or there was none). */
    const val KEY_SECURE_STORE_MIGRATED = "secure_store_v2"
    const val KEY_WAS_CONFIGURED = "was_configured"

    /**
     * Count of consecutive launches that SHOWED a screen while the secure
     * store failed to open (see SecureCredentialStore.countStoreFailureShown);
     * cleared by the next process start that opens it. A KeyStore error that
     * heals on the next launch must not offer the destructive credential
     * reset (audit 2026-10-06c N-new-1, 06d SEC-01).
     */
    const val KEY_STORE_OPEN_FAILURES = "secure_store_open_failures"

    /**
     * Plain-prefs mirrors of two facts that live in encrypted storage, so a
     * launch decision needs no keystore work and a keystore failure cannot
     * make a locked app look unlocked (fail closed). Absent = not yet
     * written by this version; callers then fall back to the secure store.
     */
    const val KEY_LOCK_ENABLED = "app_lock_enabled"
    const val KEY_CONFIGURED_HINT = "server_configured"

    // ── Secure store: the active server session ─────────────────────────

    const val KEY_SERVER_URL = "server_url"
    const val KEY_API_KEY = "api_key"
    const val KEY_SERVER_NAME = "server_name"
    const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    const val KEY_ALLOW_CLEARTEXT = "allow_cleartext"

    /** Per-profile API key: `api_key_<profileId>`. */
    fun profileApiKey(profileId: Long): String = "api_key_$profileId"

    // ── Secure store: the app-lock pattern ──────────────────────────────

    const val KEY_PATTERN_SALT = "pattern_salt"
    const val KEY_PATTERN_HASH_V2 = "pattern_hash_v2"

    /** Pre-PBKDF2 SHA-256 pattern hash; only removed now. */
    const val KEY_PATTERN_HASH_V1 = "pattern_hash"
    const val KEY_PATTERN_ENCRYPTED = "pattern_encrypted"
    const val KEY_PATTERN_IV = "pattern_iv"

    // ── Plain prefs: the app lock ───────────────────────────────────────

    const val KEY_PATTERN_KEYSTORE_BOUND = "pattern_keystore_bound"

    /** A [KEY_PATTERN_HASH_V1] lock was dropped and the user not told yet. */
    const val KEY_LEGACY_LOCK_REMOVED_NOTICE = "legacy_lock_removed_notice"

    // Persistent failure lockout
    const val KEY_PATTERN_FAIL_COUNT = "pattern_fail_count"

    /** Pre-2026-09 wall-clock deadline; only removed now. */
    const val KEY_PATTERN_LOCKOUT_UNTIL_LEGACY = "pattern_lockout_until"
    const val KEY_LOCKOUT_DURATION = "pattern_lockout_duration_ms"
    const val KEY_LOCKOUT_START_ELAPSED = "pattern_lockout_start_elapsed"
    const val KEY_LOCKOUT_BOOT = "pattern_lockout_boot_count"

    /** Whether [prefs] (the secure store, whose key names are plaintext) holds an app-lock pattern. */
    fun hasPatternIn(prefs: SharedPreferences): Boolean =
        prefs.contains(KEY_PATTERN_HASH_V2) || prefs.contains(KEY_PATTERN_ENCRYPTED)
}
