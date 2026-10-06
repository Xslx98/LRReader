package com.lanraragi.reader.client.api

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.security.GeneralSecurityException
import java.security.Key
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Thrown by [LRRAuthManager] setters when the Android KeyStore-backed credential
 * store is unavailable (e.g., after device migration or KeyStore corruption).
 *
 * Callers that write credentials MUST handle this exception and surface a user-visible
 * error — silently swallowing it leaves users believing their changes were saved.
 */
class LRRSecureStorageUnavailableException(message: String) : IOException(message)

/**
 * Manages LANraragi server connection settings (server URL and API key).
 * Stores credentials in [KeystoreSecurePrefs] (AES-GCM values under an
 * AndroidKeyStore key). Installs from before audit C49 kept them in the
 * deprecated EncryptedSharedPreferences; [initialize] copies them over once.
 */
object LRRAuthManager {

    private const val TAG = "LRRAuthManager"
    private const val INIT_TIMEOUT_MS = 10_000L
    /**
     * Main-thread bound: well under Android's 5 s input-dispatch ANR limit,
     * leaving room for the rest of the frame (audit 2026-10-06 N5).
     */
    private const val MAIN_THREAD_INIT_TIMEOUT_MS = 2_000L
    /** Legacy EncryptedSharedPreferences file, read once by [migrateLegacyStore]. */
    private const val PREF_NAME = "lrr_auth_encrypted"
    private const val SECURE_PREF_NAME = "lrr_auth_secure"
    private const val KEYSTORE_ALIAS_SECURE_PREFS = "lrr_secure_prefs_key"
    /** Plain-prefs flag: the legacy store was copied (or there was none). */
    private const val KEY_SECURE_STORE_MIGRATED = "secure_store_v2"
    private const val PLAIN_PREF_NAME = "lrr_auth_plain"
    private const val KEY_WAS_CONFIGURED = "was_configured"

    /**
     * Plain-prefs mirrors of two facts that live in encrypted storage, so a
     * launch decision needs no keystore work and a keystore failure cannot
     * make a locked app look unlocked (fail closed). Absent = not yet
     * written by this version; callers then fall back to the secure store.
     */
    private const val KEY_LOCK_ENABLED = "app_lock_enabled"
    private const val KEY_CONFIGURED_HINT = "server_configured"

    /**
     * Plain-prefs count of consecutive launches that SHOWED a screen while
     * the secure store failed to open (see [countStoreFailureShown]);
     * cleared by the next process start that opens it. A KeyStore error that
     * heals on the next launch must not offer the destructive credential
     * reset (audit 2026-10-06c N-new-1, 06d SEC-01).
     */
    private const val KEY_STORE_OPEN_FAILURES = "secure_store_open_failures"
    private const val RESET_OFFER_MIN_FAILED_LAUNCHES = 2
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_SERVER_NAME = "server_name"
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    private const val KEY_ALLOW_CLEARTEXT = "allow_cleartext"
    private const val KEY_PATTERN_SALT = "pattern_salt"
    private const val KEY_PATTERN_HASH_V2 = "pattern_hash_v2"
    /** Pre-PBKDF2 SHA-256 pattern hash; only removed now. */
    private const val KEY_PATTERN_HASH_V1 = "pattern_hash"
    /** Plain-prefs flag: a [KEY_PATTERN_HASH_V1] lock was dropped and the user not told yet. */
    private const val KEY_LEGACY_LOCK_REMOVED_NOTICE = "legacy_lock_removed_notice"
    private const val PBKDF2_ITERATIONS_V1 = 100_000  // Legacy, kept for migration
    private const val PBKDF2_ITERATIONS = 200_000
    private const val PBKDF2_KEY_BITS = 256

    // KeyStore-bound AES-GCM wrapping for pattern hash
    private const val KEYSTORE_ALIAS_PATTERN = "lrr_pattern_key"
    private const val KEY_PATTERN_ENCRYPTED = "pattern_encrypted"
    private const val KEY_PATTERN_IV = "pattern_iv"
    private const val KEY_PATTERN_KEYSTORE_BOUND = "pattern_keystore_bound"
    private const val AES_GCM_TAG_BITS = 128

    // Persistent failure lockout (stored in plain SharedPreferences)
    private const val KEY_PATTERN_FAIL_COUNT = "pattern_fail_count"
    /** Pre-2026-09 wall-clock deadline; only removed now. */
    private const val KEY_PATTERN_LOCKOUT_UNTIL_LEGACY = "pattern_lockout_until"
    private const val KEY_LOCKOUT_DURATION = "pattern_lockout_duration_ms"
    private const val KEY_LOCKOUT_START_ELAPSED = "pattern_lockout_start_elapsed"
    private const val KEY_LOCKOUT_BOOT = "pattern_lockout_boot_count"
    private const val LOCKOUT_THRESHOLD_FIRST = 5

    /**
     * Lockout after the 5th, 6th, 7th, 8th and every later consecutive
     * failure. Escalating: a flat cap let brute force run at a steady rate.
     */
    private val LOCKOUT_DURATIONS_MS = longArrayOf(30_000L, 60_000L, 300_000L, 900_000L, 3_600_000L)

    @Volatile
    private var sPrefs: SharedPreferences? = null

    /** Plain (unencrypted) SharedPreferences for lockout state and flags that must
     *  survive KeyStore failures. */
    @Volatile
    private var sPlainPrefs: SharedPreferences? = null

    @Volatile
    private var sActiveProfileId: Long = 0

    /** True when KeyStore became unavailable and the user must re-enter credentials. */
    @Volatile
    private var sNeedsReauthentication: Boolean = false

    /**
     * In-memory per-profile API-key cache. [getApiKeyForProfile] is resolved
     * by [LRRAuthInterceptor] on EVERY authenticated request (each page and
     * thumbnail fetch), and EncryptedSharedPreferences pays an AES-GCM
     * decrypt per `getString` — a 200-page bulk download would otherwise run
     * ~200 decrypts on OkHttp dispatcher threads. `""` caches the
     * absent/empty-key state. Every credential mutation path invalidates
     * ([setApiKeyForProfile], [clearApiKeyForProfile], [clear],
     * [initializeForTesting], [simulateStorageUnavailableForTesting]).
     * The plaintext-in-heap exposure is not new: the key already transits
     * the heap as a header string on every request.
     */
    private val sProfileKeyCache = ConcurrentHashMap<Long, String>()

    /**
     * Monotonically increasing counter bumped whenever the active server profile
     * is modified (URL, API key, name, cleartext flag). Observers compare against
     * their last-seen value to detect changes and trigger a refresh.
     */
    private val _serverConfigVersion = AtomicLong(0L)

    @JvmStatic
    val serverConfigVersion: Long
        get() = _serverConfigVersion.get()

    /** Bump the config version. Call after any active-profile credential write. */
    @JvmStatic
    fun bumpServerConfigVersion() {
        _serverConfigVersion.incrementAndGet()
    }

    /**
     * Monotonic clock for the lockout (overridable in tests). Deliberately not
     * wall-clock time: moving the system date forward must not end a lockout.
     */
    internal var clockMillis: () -> Long = { SystemClock.elapsedRealtime() }

    /** Boot counter; a change means [clockMillis] restarted from zero. */
    internal var bootCount: () -> Int = { readBootCount() }

    @Volatile
    private var sAppContext: Context? = null

    /** Orders the store's mirror write against [rememberLockState]'s (the store's wins). */
    private val lockMirrorWrite = Any()

    private fun readBootCount(): Int {
        val resolver = sAppContext?.contentResolver ?: return -1
        return android.provider.Settings.Global.getInt(resolver, android.provider.Settings.Global.BOOT_COUNT, -1)
    }

    // ── Async-init readiness gate (INF-9) ───────────────────────────────

    /** Set synchronously by [scheduleInitialize] BEFORE the init coroutine is
     *  launched, so no reader can take the never-scheduled fallback while the
     *  coroutine merely hasn't started running yet. */
    @Volatile
    private var sInitScheduled = false

    /** True once [initialize] (or a test seam) finished, whatever the outcome. */
    @Volatile
    private var sInitDone = false

    /** Opened by [openInitGate]. Replaced only by [resetInitGateForTesting]. */
    @Volatile
    private var sInitLatch = CountDownLatch(1)

    /** Set once a reader gave up waiting; later readers stop blocking. */
    @Volatile
    private var sInitTimedOut = false

    /**
     * Set once a main-thread reader gave up; later main-thread readers stop
     * blocking while background readers keep their longer wait.
     */
    @Volatile
    private var sMainInitTimedOut = false

    /** How long [awaitInit] waits for the gate. Tests shorten it. */
    @Volatile
    internal var initTimeoutMs: Long = INIT_TIMEOUT_MS

    /** How long [awaitInit] waits on the main thread. Tests shorten it. */
    @Volatile
    internal var mainThreadInitTimeoutMs: Long = MAIN_THREAD_INIT_TIMEOUT_MS

    /** Cipher of the secure store. Tests swap in an in-memory one (Robolectric has no AndroidKeyStore). */
    @Volatile
    internal var secureStoreCipher: () -> ValueCipher = { KeystoreValueCipher(KEYSTORE_ALIAS_SECURE_PREFS) }

    /**
     * Gate every read/write of [sPrefs]/[sPlainPrefs]/[sActiveProfileId]/
     * [sNeedsReauthentication] behind async initialization (INF-9):
     *
     * - gate open: a single volatile read (steady state, zero cost);
     * - never scheduled: return immediately — legacy semantics for unit tests
     *   that exercise the "no initialize ⇒ null-prefs defaults" paths;
     * - scheduled but pending: block until [initialize] completes. Worst case
     *   this charges the FIRST reader the remaining init time instead of
     *   unconditionally charging the main thread in Application.onCreate.
     */
    private fun awaitInit() {
        if (sInitDone) return
        if (!sInitScheduled) return
        if (sInitTimedOut) return
        // Gated getters run on the main thread during cold start (MainActivity's
        // reauthentication check, SecurityScene's storage check, drawer and list
        // setup), so the main thread gets a bound below the ANR limit (N5).
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        if (onMain && sMainInitTimedOut) return
        val timeoutMs = if (onMain) mainThreadInitTimeoutMs else initTimeoutMs
        // Bounded (audit 2026-10-04 C34 / STAB-15): a hung KeyStore binder call must
        // not block every reader forever. After the timeout readers see the
        // uninitialised defaults (as if storage were unavailable) until the gate opens.
        if (!sInitLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            if (onMain) sMainInitTimedOut = true else sInitTimedOut = true
            Log.e(TAG, "Secure storage init still pending after $timeoutMs ms; continuing without it")
            com.lanraragi.reader.diagnostics.DiagLog.e(
                TAG, "Secure storage init still pending after $timeoutMs ms; continuing without it"
            )
        }
    }

    /**
     * Launch [initialize] on [scope] instead of blocking the caller (INF-9).
     * Readers are held by [awaitInit] until the gate opens, so no call site
     * needs to change. Callers that must order work after init completes
     * (LRReaderApplication's profile loader) should `join()` the returned [Job].
     */
    @JvmStatic
    fun scheduleInitialize(context: Context, scope: CoroutineScope): Job {
        sAppContext = context.applicationContext
        sInitScheduled = true
        return scope.launch {
            Trace.beginSection("LRRApp.LRRAuthManager.init")
            try {
                initialize(context)
            } finally {
                Trace.endSection()
            }
        }
    }

    private fun openInitGate() {
        sInitDone = true
        sInitLatch.countDown()
    }

    /** Restore the never-scheduled gate state. Tests only. */
    @JvmStatic
    internal fun resetInitGateForTesting() {
        sInitScheduled = false
        sInitDone = false
        sInitTimedOut = false
        sMainInitTimedOut = false
        sStoreFailureCounted = false
        initTimeoutMs = INIT_TIMEOUT_MS
        mainThreadInitTimeoutMs = MAIN_THREAD_INIT_TIMEOUT_MS
        secureStoreCipher = { KeystoreValueCipher(KEYSTORE_ALIAS_SECURE_PREFS) }
        sInitLatch = CountDownLatch(1)
    }

    @JvmStatic
    fun initialize(context: Context) {
        // NOTE (INF-9): this method must not call any gated public accessor of
        // this object — awaitInit() would block on the not-yet-open gate from
        // the very thread that is supposed to open it. Touch fields directly.
        try {
            initializeInner(context)
        } finally {
            // Always open the gate — including on the KeyStore-failure catch
            // branches inside initializeInner and on unexpected throws (which
            // then surface via the caller's CoroutineExceptionHandler).
            openInitGate()
        }
    }

    private fun initializeInner(context: Context) {
        sAppContext = context.applicationContext
        val plainPrefs = context.applicationContext
            .getSharedPreferences(PLAIN_PREF_NAME, Context.MODE_PRIVATE)
        sPlainPrefs = plainPrefs
        try {
            val secure = KeystoreSecurePrefs.open(
                context.applicationContext.getSharedPreferences(SECURE_PREF_NAME, Context.MODE_PRIVATE),
                secureStoreCipher(),
            )
            if (!plainPrefs.getBoolean(KEY_SECURE_STORE_MIGRATED, false)) {
                migrateLegacyStore(context.applicationContext, secure)
                plainPrefs.edit(commit = true) { putBoolean(KEY_SECURE_STORE_MIGRATED, true) }
                deleteLegacyStore(context.applicationContext)
            }
            sPrefs = secure
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "KeyStore unavailable — credentials will not persist this session", e)
            // DO NOT wipe the secure store (or the legacy one) here. The values
            // are AES-GCM ciphertext (base64 in the underlying xml) and have no
            // plaintext leakage risk. Wiping makes a transient KeyStore
            // failure (system update, biometric re-enroll, etc.) permanently
            // destroy the user's API keys; preserving the blobs lets them
            // become readable again once KeyStore recovers on a later launch,
            // and an unfinished legacy migration is retried then too.
            sPrefs = null
            // Only prompt reauth if the user had previously configured a server
            sNeedsReauthentication = plainPrefs.getBoolean(KEY_WAS_CONFIGURED, false)
        } catch (e: IOException) {
            Log.e(TAG, "I/O error initializing the secure store — credentials will not persist", e)
            sPrefs = null
            sNeedsReauthentication = plainPrefs.getBoolean(KEY_WAS_CONFIGURED, false)
        } catch (e: java.security.ProviderException) {
            // KeyStore failures some devices report as a runtime exception.
            Log.e(TAG, "KeyStore provider failure — credentials will not persist this session", e)
            sPrefs = null
            sNeedsReauthentication = plainPrefs.getBoolean(KEY_WAS_CONFIGURED, false)
        }
        recordStoreOpenOutcome(plainPrefs, opened = sPrefs != null)
        // Restore active profile (falls back to 0 when sPrefs is null)
        val prefs = sPrefs
        sActiveProfileId = prefs?.getLong(KEY_ACTIVE_PROFILE_ID, 0L) ?: 0L
        // Migrate away from v1 SHA-256 pattern hash: it cannot be checked any
        // more, so the lock is dropped and the app opens unlocked. Tell the
        // user once ([consumeLegacyLockRemovedNotice], audit 06d SEC-08); the
        // notice flag is committed first so the drop is never silent.
        if (prefs?.contains(KEY_PATTERN_HASH_V1) == true) {
            if (!hasPatternIn(prefs)) {
                plainPrefs.edit(commit = true) { putBoolean(KEY_LEGACY_LOCK_REMOVED_NOTICE, true) }
            }
            prefs.edit { remove(KEY_PATTERN_HASH_V1) }
        }
        // Persist "was_configured" flag when a server URL exists, so we can detect
        // KeyStore corruption vs fresh install on next startup.
        if (prefs?.getString(KEY_SERVER_URL, null) != null) {
            plainPrefs.edit { putBoolean(KEY_WAS_CONFIGURED, true) }
        }
        if (prefs != null) {
            // Refresh the plain mirrors from the source of truth (also
            // migrates installs from before they existed).
            synchronized(lockMirrorWrite) {
                plainPrefs.edit {
                    putBoolean(KEY_LOCK_ENABLED, hasPatternIn(prefs))
                    putBoolean(KEY_CONFIGURED_HINT, !prefs.getString(KEY_SERVER_URL, null).isNullOrEmpty())
                }
            }
        }
    }

    /**
     * A store that opened ends the run of failed launches. A failed open is
     * not counted here but by [countStoreFailureShown], when a screen shows
     * it: init also runs for widget updates, download workers and other
     * background starts, which could otherwise reach the reset threshold
     * within seconds of a single KeyStore outage (audit 06c N-new-1 note).
     */
    private fun recordStoreOpenOutcome(plainPrefs: SharedPreferences, opened: Boolean) {
        if (opened && plainPrefs.contains(KEY_STORE_OPEN_FAILURES)) {
            plainPrefs.edit(commit = true) { remove(KEY_STORE_OPEN_FAILURES) }
        }
    }

    /** Set once this process counted its failed store open, see [countStoreFailureShown]. */
    @Volatile
    private var sStoreFailureCounted = false
    private val storeFailureCountLock = Any()

    /**
     * Count this process's failed store open once, the first time a screen
     * asks whether to offer a reset (the lock screen, the "credentials
     * unavailable" prompts), and return the count. So the count is of
     * launches that showed the failure to the user — including a start
     * after the lock screen's "Try again" restart: the user retried and the
     * store still failed. Call only while the store is UNAVAILABLE.
     * Committed: the lock screen's retry kills the process right after.
     */
    private fun countStoreFailureShown(): Int {
        val plain = fastPlainPrefs() ?: return 0
        synchronized(storeFailureCountLock) {
            if (!sStoreFailureCounted) {
                sStoreFailureCounted = true
                val failures = plain.getInt(KEY_STORE_OPEN_FAILURES, 0) + 1
                plain.edit(commit = true) { putInt(KEY_STORE_OPEN_FAILURES, failures) }
            }
            return plain.getInt(KEY_STORE_OPEN_FAILURES, 0)
        }
    }

    /**
     * Copy every entry of the legacy EncryptedSharedPreferences store into
     * [target] (audit C49 / SEC-17). A store that cannot be opened right now
     * (KeyStore hiccup) throws, nothing is marked migrated and the next
     * launch retries — the same outcome the old init had for that failure.
     */
    @Throws(GeneralSecurityException::class, IOException::class)
    private fun migrateLegacyStore(context: Context, target: SharedPreferences) {
        if (!legacyStoreFile(context).exists()) return
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val legacy = EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        if (!SecurePrefsMigration.copyAll(legacy, target)) {
            throw IOException("Writing the migrated secure store failed")
        }
    }

    private fun legacyStoreFile(context: Context) =
        java.io.File(context.applicationInfo.dataDir, "shared_prefs/$PREF_NAME.xml")

    /** Drop the legacy file and its master key once their content lives in the new store. */
    private fun deleteLegacyStore(context: Context) {
        context.deleteSharedPreferences(PREF_NAME)
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete the legacy credential master key", e)
        }
    }

    /**
     * Return the backing [SharedPreferences], or throw [LRRSecureStorageUnavailableException]
     * if secure storage is unavailable (KeyStore failure / EncryptedSharedPreferences init failure).
     *
     * Every setter that persists credentials MUST go through this helper so that failures
     * are surfaced to callers instead of being silently dropped.
     */
    private fun requireSecurePrefs(op: String): SharedPreferences {
        awaitInit()
        return sPrefs ?: throw LRRSecureStorageUnavailableException(
            "Secure credential store unavailable; cannot perform $op"
        )
    }

    /**
     * Inject a [SharedPreferences] instance for unit-testing environments where
     * EncryptedSharedPreferences is unavailable (e.g., Robolectric without a real KeyStore).
     * Must NOT be called from production code.
     */
    @JvmStatic
    internal fun initializeForTesting(prefs: SharedPreferences) {
        sPrefs = prefs
        sPlainPrefs = prefs
        sProfileKeyCache.clear()
        sNeedsReauthentication = false
        sActiveProfileId = prefs.getLong(KEY_ACTIVE_PROFILE_ID, 0L)
        clockMillis = { SystemClock.elapsedRealtime() }
        openInitGate()
    }

    /**
     * Simulate secure-storage unavailability for unit tests. Clears [sPrefs] so that
     * every setter throws [LRRSecureStorageUnavailableException] on the next call.
     * Must NOT be called from production code.
     */
    @JvmStatic
    internal fun simulateStorageUnavailableForTesting() {
        sPrefs = null
        sProfileKeyCache.clear()
        sActiveProfileId = 0L
        // Keep sPlainPrefs alive — lockout state must survive KeyStore failures.
    }

    /**
     * @return The configured server base URL, e.g., "http://192.168.1.100:3000"
     */
    @JvmStatic
    fun getServerUrl(): String? {
        awaitInit()
        return sPrefs?.getString(KEY_SERVER_URL, null)
    }

    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setServerUrl(url: String) {
        val prefs = requireSecurePrefs("setServerUrl")
        // Remove trailing slash
        var cleanUrl = url
        if (cleanUrl.endsWith("/")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.length - 1)
        }
        prefs.edit { putString(KEY_SERVER_URL, cleanUrl) }
        sPlainPrefs?.edit { putBoolean(KEY_CONFIGURED_HINT, cleanUrl.isNotEmpty()) }
    }

    /**
     * @return The API key (plaintext, not base64-encoded)
     */
    @JvmStatic
    fun getApiKey(): String? {
        awaitInit()
        return sPrefs?.getString(KEY_API_KEY, null)
    }

    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setApiKey(apiKey: String?) {
        val prefs = requireSecurePrefs("setApiKey")
        prefs.edit { putString(KEY_API_KEY, apiKey) }
    }

    /**
     * @return Cached server name from last successful connection
     */
    @JvmStatic
    fun getServerName(): String? {
        awaitInit()
        return sPrefs?.getString(KEY_SERVER_NAME, null)
    }

    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setServerName(name: String?) {
        val prefs = requireSecurePrefs("setServerName")
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

    /**
     * @return ID of the currently active ServerProfile (0 if none)
     */
    @JvmStatic
    fun getActiveProfileId(): Long {
        awaitInit()
        return sActiveProfileId
    }

    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setActiveProfileId(id: Long) {
        sActiveProfileId = id
        val prefs = requireSecurePrefs("setActiveProfileId")
        prefs.edit { putLong(KEY_ACTIVE_PROFILE_ID, id) }
    }

    /**
     * @return whether the active server profile permits cleartext (HTTP) requests.
     *         When sPrefs is available but the key is absent, defaults to `true`
     *         (existing HTTP profiles never wrote this key explicitly).
     *         When sPrefs is null (KeyStore failure), returns `false` (fail-closed).
     *         ServerListScene calls [setAllowCleartext] on every profile switch.
     *         Read by [LRRCleartextRejectionInterceptor] every request.
     */
    @JvmStatic
    fun getAllowCleartext(): Boolean {
        awaitInit()
        return sPrefs?.getBoolean(KEY_ALLOW_CLEARTEXT, true) ?: false
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
        val prefs = requireSecurePrefs("setAllowCleartext")
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
        val prefs = requireSecurePrefs("clearActiveSession")
        prefs.edit {
            remove(KEY_SERVER_URL)
            remove(KEY_API_KEY)
            remove(KEY_SERVER_NAME)
            remove(KEY_ALLOW_CLEARTEXT)
            putLong(KEY_ACTIVE_PROFILE_ID, 0L)
        }
        sActiveProfileId = 0L
        sPlainPrefs?.edit { putBoolean(KEY_CONFIGURED_HINT, false) }
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
        awaitInit()
        return sNeedsReauthentication
    }

    /**
     * Inspect every known server profile and mark reauthentication required if any
     * profile is missing its API key. Should be called from a background thread once
     * the Room database is ready and the full profile list has been loaded.
     *
     * Two failure modes are detected:
     *
     *   1. The encrypted backing store is unavailable ([sPrefs] is null) AND the user
     *      already has at least one profile in Room — every key was lost.
     *   2. The backing store is available but at least one profile has no entry under
     *      `api_key_$id` — partial corruption / interrupted migration.
     *
     * Both leave the user in a state where the auth interceptor would silently send
     * requests with no Bearer token; we set [sNeedsReauthentication] so MainActivity /
     * ServerListScene surface the dialog and direct the user to ServerListScene.
     */
    @JvmStatic
    fun markReauthIfProfilesUnprotected(profileIds: List<Long>) {
        awaitInit()
        if (profileIds.isEmpty()) return
        val prefs = sPrefs
        if (prefs == null) {
            // KeyStore is broken AND profiles exist in Room: keys are unrecoverable.
            sNeedsReauthentication = true
            return
        }
        // KeyStore is up but verify each profile has its api_key entry.
        for (id in profileIds) {
            if (!prefs.contains("api_key_$id")) {
                sNeedsReauthentication = true
                return
            }
        }
        // All profiles accounted for — clear any stale reauth flag.
        sNeedsReauthentication = false
    }

    // ── Per-profile API key storage (encrypted, keyed by profile ID) ──────────

    /**
     * Store the API key for a specific server profile in encrypted prefs.
     * Use this instead of storing keys in the Room `SERVER_PROFILES` table.
     */
    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setApiKeyForProfile(profileId: Long, apiKey: String?) {
        val prefs = requireSecurePrefs("setApiKeyForProfile")
        val prefKey = "api_key_$profileId"
        // Store empty string (not remove) so markReauthIfProfilesUnprotected can
        // distinguish "intentionally no key" from "key lost due to KeyStore failure".
        prefs.edit { putString(prefKey, apiKey ?: "") }
        sProfileKeyCache[profileId] = apiKey.orEmpty()
    }

    /** @return the API key for the given profile, or null if none stored / empty. */
    @JvmStatic
    fun getApiKeyForProfile(profileId: Long): String? {
        // Cache hit: no init gate, no crypto — see sProfileKeyCache KDoc.
        sProfileKeyCache[profileId]?.let { return it.ifEmpty { null } }
        awaitInit()
        // Keystore unavailable: return null WITHOUT caching, so a later
        // recovered store serves real keys instead of a poisoned miss.
        val prefs = sPrefs ?: return null
        val key = prefs.getString("api_key_$profileId", null)
        sProfileKeyCache[profileId] = key.orEmpty()
        return key?.ifEmpty { null }
    }

    /** Remove the stored API key for a profile (e.g., when the profile is deleted). */
    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun clearApiKeyForProfile(profileId: Long) {
        val prefs = requireSecurePrefs("clearApiKeyForProfile")
        prefs.edit { remove("api_key_$profileId") }
        sProfileKeyCache.remove(profileId)
    }

    // ── Persistent failure lockout ──────────────────────────────────────

    /**
     * @return true if the pattern is currently locked out due to too many failed attempts.
     */
    @JvmStatic
    fun isLockedOut(): Boolean = getLockoutRemainingMs() > 0

    /**
     * @return the remaining lockout duration in milliseconds, or 0 if not locked out.
     *
     * Measured on the monotonic clock within one boot. After a reboot the
     * clock restarts, so the lockout is re-anchored and its full duration
     * runs again — rebooting never shortens it.
     */
    @JvmStatic
    fun getLockoutRemainingMs(): Long {
        awaitInit()
        val plain = sPlainPrefs ?: return 0L
        val duration = plain.getLong(KEY_LOCKOUT_DURATION, 0L)
        if (duration <= 0L) return 0L
        val now = clockMillis()
        val start = plain.getLong(KEY_LOCKOUT_START_ELAPSED, now)
        val boot = bootCount()
        if (plain.getInt(KEY_LOCKOUT_BOOT, boot) != boot || now < start) {
            plain.edit {
                putLong(KEY_LOCKOUT_START_ELAPSED, now)
                putInt(KEY_LOCKOUT_BOOT, boot)
            }
            return duration
        }
        return (duration - (now - start)).coerceAtLeast(0L)
    }

    /**
     * Record a failed pattern attempt. Increments the persistent counter and,
     * from the 5th consecutive failure on, starts an escalating lockout
     * (30 s, 1 min, 5 min, 15 min, then 1 h for every further failure).
     */
    @JvmStatic
    fun recordFailure() {
        awaitInit()
        val plain = sPlainPrefs ?: return
        val count = plain.getInt(KEY_PATTERN_FAIL_COUNT, 0) + 1
        plain.edit {
            putInt(KEY_PATTERN_FAIL_COUNT, count)
            remove(KEY_PATTERN_LOCKOUT_UNTIL_LEGACY)
            if (count >= LOCKOUT_THRESHOLD_FIRST) {
                val level = (count - LOCKOUT_THRESHOLD_FIRST).coerceAtMost(LOCKOUT_DURATIONS_MS.size - 1)
                putLong(KEY_LOCKOUT_DURATION, LOCKOUT_DURATIONS_MS[level])
                putLong(KEY_LOCKOUT_START_ELAPSED, clockMillis())
                putInt(KEY_LOCKOUT_BOOT, bootCount())
            }
        }
    }

    /**
     * Reset the failure counter and lockout timestamp. Called on successful verification.
     */
    @JvmStatic
    fun resetFailures() {
        awaitInit()
        val plain = sPlainPrefs ?: return
        plain.edit {
            remove(KEY_PATTERN_FAIL_COUNT)
            remove(KEY_PATTERN_LOCKOUT_UNTIL_LEGACY)
            remove(KEY_LOCKOUT_DURATION)
            remove(KEY_LOCKOUT_START_ELAPSED)
            remove(KEY_LOCKOUT_BOOT)
        }
    }

    /**
     * @return the current failure count (for UI display).
     */
    @JvmStatic
    fun getFailureCount(): Int {
        awaitInit()
        return sPlainPrefs?.getInt(KEY_PATTERN_FAIL_COUNT, 0) ?: 0
    }

    // ── KeyStore-bound AES-GCM pattern wrapping ─────────────────────────

    /**
     * @return true if the stored pattern hash is bound to Android KeyStore via AES-GCM.
     * When true, [verifyPatternWithCipher] must be used instead of [verifyPattern].
     */
    @JvmStatic
    fun isPatternKeystoreBound(): Boolean {
        awaitInit()
        return sPlainPrefs?.getBoolean(KEY_PATTERN_KEYSTORE_BOUND, false) == true
    }

    /**
     * Generate or retrieve the KeyStore-backed AES key for pattern hash encryption.
     * The key requires user authentication via BiometricPrompt to use.
     *
     * @throws GeneralSecurityException if KeyStore operations fail
     */
    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun generatePatternKeystoreKey() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (keyStore.containsAlias(KEYSTORE_ALIAS_PATTERN)) return

        val keyGen = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        keyGen.init(patternKeySpec())
        keyGen.generateKey()
    }

    /**
     * The pattern key's spec: AES-GCM, usable only through a strong-biometric
     * BiometricPrompt CryptoObject, once per authentication.
     *
     * `setUserAuthenticationParameters` exists from API 30 only; on Android
     * 9/10 calling it threw NoSuchMethodError, an Error the caller's
     * fallback does not catch, so setting a pattern crashed (audit 06d
     * STAB-01). Below API 30 a validity of -1 is the same rule: per-use
     * authentication that only a biometric can satisfy, and a key that the
     * CryptoObject flow unlocks (BiometricPrompt exists from API 28).
     */
    @JvmStatic
    internal fun patternKeySpec(): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS_PATTERN,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        return builder.build()
    }

    /**
     * Whether the pattern can be checked without the keystore key, i.e. a
     * plain PBKDF2 hash is stored. False only for keystore-bound patterns
     * saved before that hash was kept.
     */
    @JvmStatic
    fun canVerifyWithoutKeystore(): Boolean {
        awaitInit()
        return sPrefs?.contains(KEY_PATTERN_HASH_V2) == true
    }

    /**
     * Drop the keystore binding (after the key was invalidated), keeping the
     * pattern itself: it stays verifiable through the plain PBKDF2 hash.
     */
    @JvmStatic
    fun unbindPatternFromKeystore() {
        awaitInit()
        sPrefs?.edit {
            remove(KEY_PATTERN_ENCRYPTED)
            remove(KEY_PATTERN_IV)
        }
        sPlainPrefs?.edit { putBoolean(KEY_PATTERN_KEYSTORE_BOUND, false) }
        deletePatternKeystoreKey()
    }

    /**
     * Delete the KeyStore-backed AES key for pattern hash encryption.
     * Called when clearing the pattern or when falling back to PBKDF2-only.
     */
    @JvmStatic
    fun deletePatternKeystoreKey() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(KEYSTORE_ALIAS_PATTERN)) {
                keyStore.deleteEntry(KEYSTORE_ALIAS_PATTERN)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete pattern KeyStore key", e)
        }
    }

    /**
     * Create a [Cipher] in ENCRYPT mode initialized with the KeyStore-backed AES key.
     * Must be called to create a CryptoObject for BiometricPrompt during [setPattern].
     *
     * @throws GeneralSecurityException if the KeyStore key is unavailable
     */
    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun getEncryptCipher(): Cipher {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val key = keyStore.getKey(KEYSTORE_ALIAS_PATTERN, null)
            ?: throw GeneralSecurityException("Pattern KeyStore key not found")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    /**
     * Create a [Cipher] in DECRYPT mode initialized with the KeyStore-backed AES key
     * and the IV stored alongside the encrypted pattern hash.
     * Must be called to create a CryptoObject for BiometricPrompt during [verifyPatternWithCipher].
     *
     * @throws GeneralSecurityException if the KeyStore key or stored IV is unavailable
     */
    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun getDecryptCipher(): Cipher {
        awaitInit()
        val prefs = sPrefs ?: throw GeneralSecurityException("Secure storage unavailable")
        val ivStr = prefs.getString(KEY_PATTERN_IV, null)
            ?: throw PatternKeyMissingException("Pattern IV not found")
        val iv = Base64.decode(ivStr, Base64.NO_WRAP)
        val key = patternKeyLoader()
            ?: throw PatternKeyMissingException("Pattern KeyStore key not found")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // A key invalidated by a new fingerprint enrolment throws
        // KeyPermanentlyInvalidatedException here.
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(AES_GCM_TAG_BITS, iv))
        return cipher
    }

    /** The pattern key or its IV is gone for good: no fingerprint proof is possible until the pattern is set again. */
    class PatternKeyMissingException(message: String) : GeneralSecurityException(message)

    /** Loads the pattern key. Tests swap in a software key (Robolectric has no AndroidKeyStore). */
    internal var patternKeyLoader: () -> Key? = {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        keyStore.getKey(KEYSTORE_ALIAS_PATTERN, null)
    }

    /**
     * Whether [authenticatedCipher] — the CryptoObject cipher returned by a
     * successful fingerprint BiometricPrompt — really is the pattern key,
     * unlocked by that authentication (audit 2026-10-06e SEC-01). The prompt's
     * success callback alone proves only that *some* enrolled finger matched,
     * including one added after the lock was set by anyone who knows the
     * device PIN. The key is created with `setUserAuthenticationRequired`
     * and the default `setInvalidatedByBiometricEnrollment(true)`, so after
     * such an enrolment it cannot be initialised at all, and without the
     * biometric it cannot be used: decrypting the stored blob (GCM checks
     * its tag, so a wrong key fails) is the proof.
     *
     * Scope (audit SEC-02): this binds the *fingerprint* unlock only. The
     * pattern itself is also stored as a plain PBKDF2 hash beside the
     * encrypted copy, so the binding adds nothing against an attacker who
     * can read the secure store; it is not a second factor for the pattern.
     */
    @JvmStatic
    fun verifyFingerprintCipher(authenticatedCipher: Cipher?): Boolean {
        if (authenticatedCipher == null) return false
        awaitInit()
        val prefs = sPrefs ?: return false
        val encryptedStr = prefs.getString(KEY_PATTERN_ENCRYPTED, null) ?: return false
        return try {
            val decrypted = authenticatedCipher.doFinal(Base64.decode(encryptedStr, Base64.NO_WRAP))
            val plainStr = prefs.getString(KEY_PATTERN_HASH_V2, null)
            plainStr == null || MessageDigest.isEqual(decrypted, Base64.decode(plainStr, Base64.NO_WRAP))
        } catch (e: Exception) {
            Log.e(TAG, "Fingerprint cipher did not decrypt the pattern blob", e)
            false
        }
    }

    // ── App-lock pattern (PBKDF2WithHmacSHA256 + optional KeyStore AES-GCM) ──

    /** @return true if an app-lock pattern has been stored. */
    @JvmStatic
    fun hasPattern(): Boolean {
        awaitInit()
        val prefs = sPrefs ?: return false
        return hasPatternIn(prefs)
    }

    private fun hasPatternIn(prefs: SharedPreferences): Boolean =
        prefs.contains(KEY_PATTERN_HASH_V2) || prefs.contains(KEY_PATTERN_ENCRYPTED)

    /** Plain prefs readable without waiting for (or depending on) the keystore. */
    private fun fastPlainPrefs(): SharedPreferences? =
        sPlainPrefs ?: sAppContext?.getSharedPreferences(PLAIN_PREF_NAME, Context.MODE_PRIVATE)

    /**
     * Whether an app lock is set, from the plain mirror — true even when the
     * secure store (and so the pattern itself) is unreadable. Null when the
     * mirror has not been written yet.
     */
    @JvmStatic
    fun lockEnabledHint(): Boolean? {
        val plain = fastPlainPrefs() ?: return null
        return if (plain.contains(KEY_LOCK_ENABLED)) plain.getBoolean(KEY_LOCK_ENABLED, false) else null
    }

    /**
     * Whether an app lock is set, FAILING CLOSED: true whenever a lock
     * cannot be ruled out (audit 2026-10-06c SEC-01). Reads the plain mirror
     * ([lockEnabledHint]). Without it — first launch after upgrading from a
     * version that never wrote it (<= v1.26), when the store is slow or
     * cannot be opened — it decides from what is readable without the
     * keystore (see [patternEvidenceWithoutKeystore]) and writes the mirror.
     * Only a legacy store that is still to be migrated cannot be read that
     * way: the store gets its bounded chance to open (which writes the
     * mirror), and if it does not, the answer is "locked". The lock screen
     * then offers its retry and, once the store failed, its reset, and lets
     * the user in as soon as the store opens without a pattern.
     *
     * False is therefore always a definite answer, which AppLockGate
     * may keep for the whole foreground session.
     */
    @JvmStatic
    fun isLockSetOrUnknown(): Boolean {
        val hint = lockEnabledHint()
        if (hint != null) return hint
        val evidence = patternEvidenceWithoutKeystore()
        if (evidence != null) {
            rememberLockState(evidence)
            return evidence
        }
        awaitInit()
        return lockEnabledHint() ?: true
    }

    /**
     * Whether a pattern is stored, from plaintext only: true / false, or null
     * when the legacy EncryptedSharedPreferences store (whose key names are
     * encrypted) has not been migrated yet. The legacy check comes first:
     * the migration copies into the new store before it marks itself done
     * and deletes the legacy file, so once the legacy store is seen as gone
     * the new store's slots read next are complete.
     */
    private fun patternEvidenceWithoutKeystore(): Boolean? {
        val plain = fastPlainPrefs()
        val context = sAppContext
        val legacyPending = context != null &&
            plain?.getBoolean(KEY_SECURE_STORE_MIGRATED, false) != true &&
            legacyStoreFile(context).exists()
        // Written with the pattern and removed with it by every version since v1.12.4.
        val boundFlag = plain?.contains(KEY_PATTERN_KEYSTORE_BOUND) == true
        // KeystoreSecurePrefs keeps its key names in plaintext.
        val opened = sPrefs
        val inStore = (opened != null && hasPatternIn(opened)) ||
            (context != null && hasPatternIn(context.getSharedPreferences(SECURE_PREF_NAME, Context.MODE_PRIVATE)))
        return when {
            boundFlag || inStore -> true
            legacyPending -> null
            else -> false
        }
    }

    /**
     * Write the lock mirror decided without the keystore, unless the store's
     * init (the source of truth) wrote it meanwhile.
     */
    private fun rememberLockState(locked: Boolean) {
        val plain = fastPlainPrefs()
        if (plain == null) return
        synchronized(lockMirrorWrite) {
            if (!plain.contains(KEY_LOCK_ENABLED)) plain.edit { putBoolean(KEY_LOCK_ENABLED, locked) }
        }
    }

    /** Whether a server URL is set, from the plain mirror; null when unknown. */
    @JvmStatic
    fun configuredHint(): Boolean? {
        val plain = fastPlainPrefs() ?: return null
        return if (plain.contains(KEY_CONFIGURED_HINT)) plain.getBoolean(KEY_CONFIGURED_HINT, false) else null
    }

    /**
     * [isConfigured] without waiting for the keystore when the plain mirror
     * is known — for the synchronous launch decision.
     */
    @JvmStatic
    fun isConfiguredFast(): Boolean = configuredHint() ?: isConfigured()

    /** False when the secure credential store could not be opened this process. */
    @JvmStatic
    fun isSecureStorageAvailable(): Boolean {
        awaitInit()
        return sPrefs != null
    }

    /**
     * True while the store's async init has not finished, i.e. a reader gave
     * up waiting for it ([isSecureStorageAvailable] is then false). Unlike an
     * init that finished and failed, this is transient — the store may open a
     * moment later — so it must not offer the destructive app-lock reset
     * (audit SEC-04).
     */
    @JvmStatic
    fun isSecureStorageStillStarting(): Boolean = sInitScheduled && !sInitDone

    /** The secure store's state as one consistent reading, see [secureStorageState]. */
    enum class SecureStorageState {
        /** The store is open. */
        AVAILABLE,

        /** Init has not finished (a reader gave up waiting): transient. */
        STARTING,

        /** Init finished and the store could not be opened. */
        UNAVAILABLE,
    }

    /**
     * [isSecureStorageAvailable] and [isSecureStorageStillStarting] in one
     * reading. Asked one after the other, an init that finished in between
     * read as "not available and not starting", i.e. failed (audit 06b N11).
     * Here the init flag is read first: init publishes the store before it
     * marks itself done, so once done is seen the store read next is final.
     */
    @JvmStatic
    fun secureStorageState(): SecureStorageState {
        awaitInit()
        val starting = sInitScheduled && !sInitDone
        val prefs = sPrefs
        return when {
            prefs != null -> SecureStorageState.AVAILABLE
            starting -> SecureStorageState.STARTING
            else -> SecureStorageState.UNAVAILABLE
        }
    }

    /**
     * Launches in a row that showed the user a failed store open (see
     * [countStoreFailureShown]), 0 after a start that opened it.
     */
    @JvmStatic
    fun consecutiveStoreOpenFailures(): Int = fastPlainPrefs()?.getInt(KEY_STORE_OPEN_FAILURES, 0) ?: 0

    /**
     * Whether the "credentials unavailable" prompts outside the lock screen
     * may offer [resetAppLockAndCredentials] (audit 2026-10-06b REL-01): the
     * store finished opening and failed (never while it is still starting),
     * it also failed on the launch before (a KeyStore error that heals by
     * the next launch must not cost the user every API key, audit 06c
     * N-new-1), and no app lock is set. A store copied from another phone,
     * or one whose keystore key was lost, never becomes readable again, and
     * a user without a lock never sees the lock screen's reset. With a lock
     * set, the lock screen owns the reset.
     */
    @JvmStatic
    fun canOfferCredentialReset(): Boolean =
        secureStorageState() == SecureStorageState.UNAVAILABLE &&
            countStoreFailureShown() >= RESET_OFFER_MIN_FAILED_LAUNCHES &&
            !isLockSetOrUnknown()

    /**
     * Whether the lock screen's "store unavailable" prompt may offer the
     * reset: the same two-launch rule as [canOfferCredentialReset] (audit
     * 2026-10-06d SEC-01). On the first failed launch it offers only a
     * retry, as the failure may heal by itself.
     */
    @JvmStatic
    fun canOfferLockScreenReset(): Boolean =
        secureStorageState() == SecureStorageState.UNAVAILABLE &&
            countStoreFailureShown() >= RESET_OFFER_MIN_FAILED_LAUNCHES

    /**
     * Whether an app lock is set, from what is readable without the
     * keystore: true / false, or null when that cannot be told (a legacy
     * store not yet migrated and no mirror). Unlike [isLockSetOrUnknown]
     * it does not fail closed; for wording only, never for gating.
     */
    @JvmStatic
    fun lockStateWithoutKeystore(): Boolean? = lockEnabledHint() ?: patternEvidenceWithoutKeystore()

    /**
     * True once after init dropped an app lock saved as the pre-PBKDF2
     * SHA-256 hash (audit 2026-10-06d SEC-08): it cannot be checked, so the
     * app now opens unlocked, and the user must be told to set a pattern
     * again. Clears the flag.
     */
    @JvmStatic
    fun consumeLegacyLockRemovedNotice(): Boolean {
        awaitInit()
        val plain = fastPlainPrefs()
        val pending = plain?.getBoolean(KEY_LEGACY_LOCK_REMOVED_NOTICE, false) == true
        if (pending) plain?.edit { remove(KEY_LEGACY_LOCK_REMOVED_NOTICE) }
        return pending
    }

    /**
     * Last resort when the secure store is unreadable: drop the encrypted
     * store (pattern, API keys, server URL) and its master key, and clear
     * the lock state, so the next process start begins unlocked with no
     * saved credentials. The caller restarts the process.
     */
    @JvmStatic
    fun resetAppLockAndCredentials(context: Context) {
        awaitInit()
        sProfileKeyCache.clear()
        sPrefs = null
        val appContext = context.applicationContext
        appContext.deleteSharedPreferences(SECURE_PREF_NAME)
        try {
            KeystoreValueCipher(KEYSTORE_ALIAS_SECURE_PREFS).deleteKey()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete the credential key", e)
        }
        deleteLegacyStore(appContext)
        deletePatternKeystoreKey()
        fastPlainPrefs()?.edit(commit = true) {
            putBoolean(KEY_LOCK_ENABLED, false)
            putBoolean(KEY_CONFIGURED_HINT, false)
            remove(KEY_WAS_CONFIGURED)
            remove(KEY_STORE_OPEN_FAILURES)
            remove(KEY_PATTERN_KEYSTORE_BOUND)
            remove(KEY_PATTERN_FAIL_COUNT)
            remove(KEY_PATTERN_LOCKOUT_UNTIL_LEGACY)
            remove(KEY_LOCKOUT_DURATION)
            remove(KEY_LOCKOUT_START_ELAPSED)
            remove(KEY_LOCKOUT_BOOT)
        }
    }

    /**
     * Hash [pattern] with PBKDF2WithHmacSHA256 (200K iterations) and persist to encrypted prefs.
     * Pass null or empty string to clear the pattern.
     *
     * For PBKDF2-only mode (no biometrics), stores the hash directly.
     */
    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setPattern(pattern: String?) {
        val prefs = requireSecurePrefs("setPattern")
        if (pattern.isNullOrEmpty()) {
            prefs.edit {
                remove(KEY_PATTERN_HASH_V2)
                remove(KEY_PATTERN_SALT)
                remove(KEY_PATTERN_ENCRYPTED)
                remove(KEY_PATTERN_IV)
            }
            sPlainPrefs?.edit {
                remove(KEY_PATTERN_KEYSTORE_BOUND)
                putBoolean(KEY_LOCK_ENABLED, false)
            }
            deletePatternKeystoreKey()
            resetFailures()
            return
        }
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val patChars = pattern.toCharArray()
        val spec = PBEKeySpec(patChars, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val hash = factory.generateSecret(spec).encoded
            prefs.edit {
                putString(KEY_PATTERN_HASH_V2, Base64.encodeToString(hash, Base64.NO_WRAP))
                putString(KEY_PATTERN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                remove(KEY_PATTERN_ENCRYPTED)
                remove(KEY_PATTERN_IV)
            }
            sPlainPrefs?.edit {
                putBoolean(KEY_PATTERN_KEYSTORE_BOUND, false)
                putBoolean(KEY_LOCK_ENABLED, true)
            }
        } catch (e: Exception) {
            throw RuntimeException("PBKDF2WithHmacSHA256 not available on this device", e)
        } finally {
            spec.clearPassword()
            patChars.fill('\u0000')
        }
    }

    /**
     * Hash [pattern] with PBKDF2, then encrypt the hash with the [authenticatedCipher]
     * (which was unlocked via BiometricPrompt). Stores the encrypted hash + IV.
     *
     * @param pattern the raw pattern string
     * @param authenticatedCipher a Cipher obtained from BiometricPrompt's CryptoObject
     */
    @JvmStatic
    @Throws(LRRSecureStorageUnavailableException::class)
    fun setPatternWithCipher(pattern: String?, authenticatedCipher: Cipher) {
        val prefs = requireSecurePrefs("setPatternWithCipher")
        if (pattern.isNullOrEmpty()) {
            setPattern(null)
            return
        }
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val patChars = pattern.toCharArray()
        val spec = PBEKeySpec(patChars, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val hash = factory.generateSecret(spec).encoded
            // Encrypt the PBKDF2 hash with the KeyStore-backed AES-GCM cipher
            val encrypted = authenticatedCipher.doFinal(hash)
            val iv = authenticatedCipher.iv
            prefs.edit {
                putString(KEY_PATTERN_ENCRYPTED, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                putString(KEY_PATTERN_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                putString(KEY_PATTERN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                // Keep the plain PBKDF2 hash too: the keystore key is
                // invalidated whenever a new fingerprint is enrolled, and
                // without this hash the pattern could never be verified again.
                putString(KEY_PATTERN_HASH_V2, Base64.encodeToString(hash, Base64.NO_WRAP))
            }
            sPlainPrefs?.edit {
                putBoolean(KEY_PATTERN_KEYSTORE_BOUND, true)
                putBoolean(KEY_LOCK_ENABLED, true)
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to encrypt pattern hash with KeyStore cipher", e)
        } finally {
            spec.clearPassword()
            patChars.fill('\u0000')
        }
    }

    /**
     * Verify [input] against the stored PBKDF2 hash using a timing-safe comparison.
     * Only works for non-KeyStore-bound patterns. For KeyStore-bound patterns,
     * use [verifyPatternWithCipher].
     *
     * Checks lockout state first; on failure, records the attempt.
     *
     * Transparent migration: if the stored hash was created with 100K iterations (V1),
     * the hash is verified against V1 parameters and then re-hashed with 200K iterations.
     *
     * @return true if input matches the stored pattern.
     */
    @JvmStatic
    fun verifyPattern(input: String?): Boolean {
        awaitInit()
        if (isLockedOut()) return false
        val prefs = sPrefs ?: return false
        val saltStr = prefs.getString(KEY_PATTERN_SALT, null) ?: return false
        val hashStr = prefs.getString(KEY_PATTERN_HASH_V2, null) ?: return false
        val salt = Base64.decode(saltStr, Base64.NO_WRAP)
        val expected = Base64.decode(hashStr, Base64.NO_WRAP)
        val patChars = (input ?: "").toCharArray()
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")

            // Try current iteration count first
            val specCurrent = PBEKeySpec(patChars, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
            try {
                val actual = factory.generateSecret(specCurrent).encoded
                if (MessageDigest.isEqual(actual, expected)) {
                    resetFailures()
                    return true
                }
            } finally {
                specCurrent.clearPassword()
            }

            // Try legacy iteration count for transparent migration
            val specLegacy = PBEKeySpec(patChars, salt, PBKDF2_ITERATIONS_V1, PBKDF2_KEY_BITS)
            try {
                val actual = factory.generateSecret(specLegacy).encoded
                if (MessageDigest.isEqual(actual, expected)) {
                    // Re-hash with current iteration count and save
                    val specMigrate = PBEKeySpec(patChars, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
                    try {
                        val newHash = factory.generateSecret(specMigrate).encoded
                        prefs.edit {
                            putString(
                                KEY_PATTERN_HASH_V2,
                                Base64.encodeToString(newHash, Base64.NO_WRAP)
                            )
                        }
                    } finally {
                        specMigrate.clearPassword()
                    }
                    resetFailures()
                    return true
                }
            } finally {
                specLegacy.clearPassword()
            }

            recordFailure()
            return false
        } catch (_: Exception) {
            recordFailure()
            return false
        } finally {
            patChars.fill('\u0000')
        }
    }

    /**
     * Verify [input] against the stored KeyStore-encrypted PBKDF2 hash.
     * The [authenticatedCipher] must have been obtained from BiometricPrompt's CryptoObject.
     *
     * Checks lockout state first; on failure, records the attempt.
     *
     * Transparent migration: if the stored hash was created with 100K iterations (V1),
     * the hash is verified against V1 parameters and then re-hashed with 200K iterations.
     * Note: the re-encrypted ciphertext uses the same AES-GCM cipher, so the KeyStore
     * binding is preserved.
     *
     * @return true if input matches the stored pattern.
     */
    @JvmStatic
    fun verifyPatternWithCipher(input: String?, authenticatedCipher: Cipher): Boolean {
        awaitInit()
        if (isLockedOut()) return false
        val prefs = sPrefs ?: return false
        val saltStr = prefs.getString(KEY_PATTERN_SALT, null) ?: return false
        val encryptedStr = prefs.getString(KEY_PATTERN_ENCRYPTED, null) ?: return false
        val salt = Base64.decode(saltStr, Base64.NO_WRAP)
        val encrypted = Base64.decode(encryptedStr, Base64.NO_WRAP)
        val patChars = (input ?: "").toCharArray()
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            // Decrypt the stored hash using the BiometricPrompt-authenticated cipher
            val expected = authenticatedCipher.doFinal(encrypted)

            // Try current iteration count first
            val specCurrent = PBEKeySpec(patChars, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
            try {
                val actual = factory.generateSecret(specCurrent).encoded
                if (MessageDigest.isEqual(actual, expected)) {
                    // Self-heal patterns saved before the plain hash was kept
                    // alongside the encrypted one (see setPatternWithCipher).
                    if (!prefs.contains(KEY_PATTERN_HASH_V2)) {
                        prefs.edit { putString(KEY_PATTERN_HASH_V2, Base64.encodeToString(actual, Base64.NO_WRAP)) }
                    }
                    resetFailures()
                    return true
                }
            } finally {
                specCurrent.clearPassword()
            }

            // Try legacy iteration count for transparent migration
            val specLegacy = PBEKeySpec(patChars, salt, PBKDF2_ITERATIONS_V1, PBKDF2_KEY_BITS)
            try {
                val actual = factory.generateSecret(specLegacy).encoded
                if (MessageDigest.isEqual(actual, expected)) {
                    // Re-hash with current iteration count — re-encryption with the same
                    // cipher is not possible (GCM cipher is single-use), so we store the
                    // new PBKDF2 hash directly in the unencrypted field and clear the
                    // encrypted field. The next setPatternWithCipher call (e.g., on
                    // pattern change) will re-encrypt with KeyStore.
                    val specMigrate = PBEKeySpec(patChars, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
                    try {
                        val newHash = factory.generateSecret(specMigrate).encoded
                        prefs.edit {
                            putString(
                                KEY_PATTERN_HASH_V2,
                                Base64.encodeToString(newHash, Base64.NO_WRAP)
                            )
                            remove(KEY_PATTERN_ENCRYPTED)
                            remove(KEY_PATTERN_IV)
                        }
                        sPlainPrefs?.edit { putBoolean(KEY_PATTERN_KEYSTORE_BOUND, false) }
                    } finally {
                        specMigrate.clearPassword()
                    }
                    resetFailures()
                    return true
                }
            } finally {
                specLegacy.clearPassword()
            }

            recordFailure()
            return false
        } catch (_: Exception) {
            recordFailure()
            return false
        } finally {
            patChars.fill('\u0000')
        }
    }

    /**
     * Clear all stored credentials.
     */
    @JvmStatic
    fun clear() {
        awaitInit()
        sProfileKeyCache.clear()
        sPrefs?.edit { clear() }
        sPlainPrefs?.edit {
            remove(KEY_PATTERN_KEYSTORE_BOUND)
            remove(KEY_LOCK_ENABLED)
            remove(KEY_CONFIGURED_HINT)
            remove(KEY_PATTERN_FAIL_COUNT)
            remove(KEY_PATTERN_LOCKOUT_UNTIL_LEGACY)
            remove(KEY_LOCKOUT_DURATION)
            remove(KEY_LOCKOUT_START_ELAPSED)
            remove(KEY_LOCKOUT_BOOT)
        }
        deletePatternKeystoreKey()
        sActiveProfileId = 0
        sNeedsReauthentication = false
    }
}
