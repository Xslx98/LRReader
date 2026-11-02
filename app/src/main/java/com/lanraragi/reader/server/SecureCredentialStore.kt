package com.lanraragi.reader.server

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import androidx.core.content.edit
import com.lanraragi.reader.server.CredentialStoreLayout.KEYSTORE_ALIAS_PATTERN
import com.lanraragi.reader.server.CredentialStoreLayout.KEYSTORE_ALIAS_SECURE_PREFS
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_ACTIVE_PROFILE_ID
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_CONFIGURED_HINT
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_LEGACY_LOCK_REMOVED_NOTICE
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_LOCKOUT_BOOT
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_LOCKOUT_DURATION
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_LOCKOUT_START_ELAPSED
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_LOCK_ENABLED
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_PATTERN_FAIL_COUNT
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_PATTERN_HASH_V1
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_PATTERN_KEYSTORE_BOUND
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_PATTERN_LOCKOUT_UNTIL_LEGACY
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_SECURE_STORE_MIGRATED
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_SERVER_URL
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_STORE_OPEN_FAILURES
import com.lanraragi.reader.server.CredentialStoreLayout.KEY_WAS_CONFIGURED
import com.lanraragi.reader.server.CredentialStoreLayout.LEGACY_MASTER_KEY_ALIAS
import com.lanraragi.reader.server.CredentialStoreLayout.PLAIN_PREF_NAME
import com.lanraragi.reader.server.CredentialStoreLayout.PREF_NAME
import com.lanraragi.reader.server.CredentialStoreLayout.SECURE_PREF_NAME
import com.lanraragi.reader.server.CredentialStoreLayout.hasPatternIn
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.Key
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Thrown by [ServerSession] and app-lock setters when the Android KeyStore-backed
 * credential store is unavailable (e.g., after device migration or KeyStore corruption).
 *
 * Callers that write credentials MUST handle this exception and surface a user-visible
 * error — silently swallowing it leaves users believing their changes were saved.
 */
class LRRSecureStorageUnavailableException(message: String) : IOException(message)

/**
 * The encrypted credential store shared by the server session ([ServerSession])
 * and the app lock (`security.AppLockCredentials`): its async open behind a
 * readiness gate, the state of that open, and the process-wide state both
 * build on. Values live in [KeystoreSecurePrefs] (AES-GCM under an
 * AndroidKeyStore key); flags that must survive a KeyStore failure live in
 * plain prefs. Installs from before audit C49 kept credentials in the
 * deprecated EncryptedSharedPreferences, which can no longer be read;
 * [initialize] asks for them again once (see [abandonLegacyStore]). The file
 * and key names are in [CredentialStoreLayout].
 *
 * All mutable state of the session and the lock lives here, so the two
 * facades stay stateless.
 */
object SecureCredentialStore {

    private const val TAG = "SecureCredentialStore"
    private const val INIT_TIMEOUT_MS = 10_000L
    /**
     * Main-thread bound: well under Android's 5 s input-dispatch ANR limit,
     * leaving room for the rest of the frame (audit 2026-10-06 N5).
     */
    private const val MAIN_THREAD_INIT_TIMEOUT_MS = 2_000L

    /** Failed launches shown to the user before a destructive reset may be offered. */
    internal const val RESET_OFFER_MIN_FAILED_LAUNCHES = 2

    /** The open secure store; null before init or when it could not be opened. */
    @Volatile
    internal var prefs: SharedPreferences? = null

    /** Plain (unencrypted) SharedPreferences for lockout state and flags that must
     *  survive KeyStore failures. */
    @Volatile
    internal var plainPrefs: SharedPreferences? = null

    @Volatile
    internal var activeProfileId: Long = 0

    /** True when KeyStore became unavailable and the user must re-enter credentials. */
    @Volatile
    internal var needsReauthentication: Boolean = false

    /**
     * In-memory per-profile API-key cache. [ServerSession.getApiKeyForProfile]
     * is resolved by [LRRAuthInterceptor] on EVERY authenticated request (each
     * page and thumbnail fetch), and the secure store pays an AES-GCM decrypt
     * per `getString` — a 200-page bulk download would otherwise run ~200
     * decrypts on OkHttp dispatcher threads. `""` caches the absent/empty-key
     * state. Every credential mutation path invalidates
     * ([ServerSession.setApiKeyForProfile], [ServerSession.clearApiKeyForProfile],
     * [clear], [wipe], [initializeForTesting], [simulateStorageUnavailableForTesting]).
     * The plaintext-in-heap exposure is not new: the key already transits
     * the heap as a header string on every request.
     */
    internal val profileKeyCache = ConcurrentHashMap<Long, String>()

    /** Backs [ServerSession.serverConfigVersion]. */
    internal val serverConfigVersion = AtomicLong(0L)

    /**
     * Monotonic clock for the app-lock lockout (overridable in tests).
     * Deliberately not wall-clock time: moving the system date forward must
     * not end a lockout.
     */
    internal var clockMillis: () -> Long = { SystemClock.elapsedRealtime() }

    /** Boot counter; a change means [clockMillis] restarted from zero. */
    internal var bootCount: () -> Int = { readBootCount() }

    /** Loads the pattern key. Tests swap in a software key (Robolectric has no AndroidKeyStore). */
    internal var patternKeyLoader: () -> Key? = {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        keyStore.getKey(KEYSTORE_ALIAS_PATTERN, null)
    }

    @Volatile
    internal var appContext: Context? = null

    /** Orders the store's mirror write against the lock's own (the store's wins). */
    internal val lockMirrorWrite = Any()

    private fun readBootCount(): Int {
        val resolver = appContext?.contentResolver ?: return -1
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
     * Gate every read/write of [prefs]/[plainPrefs]/[activeProfileId]/
     * [needsReauthentication] behind async initialization (INF-9):
     *
     * - gate open: a single volatile read (steady state, zero cost);
     * - never scheduled: return immediately — legacy semantics for unit tests
     *   that exercise the "no initialize ⇒ null-prefs defaults" paths;
     * - scheduled but pending: block until [initialize] completes. Worst case
     *   this charges the FIRST reader the remaining init time instead of
     *   unconditionally charging the main thread in Application.onCreate.
     */
    internal fun awaitInit() {
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
        appContext = context.applicationContext
        sInitScheduled = true
        return scope.launch {
            Trace.beginSection("LRRApp.SecureCredentialStore.init")
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
        // NOTE (INF-9): this method must not call any gated accessor (of this
        // object, ServerSession or AppLockCredentials) — awaitInit() would
        // block on the not-yet-open gate from the very thread that is
        // supposed to open it. Touch fields directly.
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
        appContext = context.applicationContext
        val plain = context.applicationContext
            .getSharedPreferences(PLAIN_PREF_NAME, Context.MODE_PRIVATE)
        plainPrefs = plain
        try {
            val secure = KeystoreSecurePrefs.open(
                context.applicationContext.getSharedPreferences(SECURE_PREF_NAME, Context.MODE_PRIVATE),
                secureStoreCipher(),
            )
            if (!plain.getBoolean(KEY_SECURE_STORE_MIGRATED, false)) {
                abandonLegacyStore(context.applicationContext, plain, secure)
                plain.edit(commit = true) { putBoolean(KEY_SECURE_STORE_MIGRATED, true) }
                deleteLegacyStore(context.applicationContext)
            }
            prefs = secure
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "KeyStore unavailable — credentials will not persist this session", e)
            // DO NOT wipe the secure store (or the legacy one) here. The values
            // are AES-GCM ciphertext (base64 in the underlying xml) and have no
            // plaintext leakage risk. Wiping makes a transient KeyStore
            // failure (system update, biometric re-enroll, etc.) permanently
            // destroy the user's API keys; preserving the blobs lets them
            // become readable again once KeyStore recovers on a later launch,
            // and an unfinished legacy migration is retried then too.
            prefs = null
            // Only prompt reauth if the user had previously configured a server
            needsReauthentication = plain.getBoolean(KEY_WAS_CONFIGURED, false)
        } catch (e: IOException) {
            Log.e(TAG, "I/O error initializing the secure store — credentials will not persist", e)
            prefs = null
            needsReauthentication = plain.getBoolean(KEY_WAS_CONFIGURED, false)
        } catch (e: java.security.ProviderException) {
            // KeyStore failures some devices report as a runtime exception.
            Log.e(TAG, "KeyStore provider failure — credentials will not persist this session", e)
            prefs = null
            needsReauthentication = plain.getBoolean(KEY_WAS_CONFIGURED, false)
        }
        recordStoreOpenOutcome(plain, opened = prefs != null)
        // Restore active profile (falls back to 0 when prefs is null)
        val opened = prefs
        activeProfileId = opened?.getLong(KEY_ACTIVE_PROFILE_ID, 0L) ?: 0L
        // Migrate away from v1 SHA-256 pattern hash: it cannot be checked any
        // more, so the lock is dropped and the app opens unlocked. Tell the
        // user once (AppLockCredentials.isLegacyLockRemovedNoticePending, audit
        // 06d SEC-08); the notice flag is committed first so the drop is never silent.
        if (opened?.contains(KEY_PATTERN_HASH_V1) == true) {
            if (!hasPatternIn(opened)) {
                plain.edit(commit = true) { putBoolean(KEY_LEGACY_LOCK_REMOVED_NOTICE, true) }
            }
            opened.edit { remove(KEY_PATTERN_HASH_V1) }
        }
        // Persist "was_configured" flag when a server URL exists, so we can detect
        // KeyStore corruption vs fresh install on next startup.
        if (opened?.getString(KEY_SERVER_URL, null) != null) {
            plain.edit { putBoolean(KEY_WAS_CONFIGURED, true) }
        }
        if (opened != null) {
            // Refresh the plain mirrors from the source of truth (also
            // migrates installs from before they existed).
            synchronized(lockMirrorWrite) {
                plain.edit {
                    putBoolean(KEY_LOCK_ENABLED, hasPatternIn(opened))
                    putBoolean(KEY_CONFIGURED_HINT, !opened.getString(KEY_SERVER_URL, null).isNullOrEmpty())
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
    private fun recordStoreOpenOutcome(plain: SharedPreferences, opened: Boolean) {
        if (opened && plain.contains(KEY_STORE_OPEN_FAILURES)) {
            plain.edit(commit = true) { remove(KEY_STORE_OPEN_FAILURES) }
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
    internal fun countStoreFailureShown(): Int {
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
     * Installs from before v2.0 kept credentials in androidx.security's
     * EncryptedSharedPreferences; v2.0 and v2.1 copied them into
     * [KeystoreSecurePrefs] on first launch (audit C49 / SEC-17). That
     * library is gone (ruling R26), so an install updated straight from v1.x
     * cannot read them any more: the API keys must be entered again and an
     * app lock set there is lost. Both are said rather than left silent —
     * the startup reauth prompt for the keys, the lock-removed notice
     * (AppLockCredentials.isLegacyLockRemovedNoticePending) for the lock. The
     * caller then marks the store handled and deletes it.
     */
    private fun abandonLegacyStore(context: Context, plain: SharedPreferences, target: SharedPreferences) {
        if (!legacyStoreFile(context).exists()) return
        Log.e(TAG, "Credentials saved before v2.0 cannot be read; asking for them again")
        // Plain flag every version since v1.12.4 writes with the pattern.
        if (plain.contains(KEY_PATTERN_KEYSTORE_BOUND) && !hasPatternIn(target)) {
            plain.edit(commit = true) {
                putBoolean(KEY_LEGACY_LOCK_REMOVED_NOTICE, true)
                remove(KEY_PATTERN_KEYSTORE_BOUND)
            }
            deletePatternKeystoreKey()
        }
        if (plain.getBoolean(KEY_WAS_CONFIGURED, false)) {
            needsReauthentication = true
        }
    }

    internal fun legacyStoreFile(context: Context) =
        java.io.File(context.applicationInfo.dataDir, "shared_prefs/$PREF_NAME.xml")

    /** Drop the legacy file and its master key (see [abandonLegacyStore]). */
    private fun deleteLegacyStore(context: Context) {
        context.deleteSharedPreferences(PREF_NAME)
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(LEGACY_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(LEGACY_MASTER_KEY_ALIAS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete the legacy credential master key", e)
        }
    }

    /**
     * Delete the KeyStore-backed AES key for pattern hash encryption.
     * Called when clearing the pattern or when falling back to PBKDF2-only.
     */
    internal fun deletePatternKeystoreKey() {
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
     * Return the backing [SharedPreferences], or throw [LRRSecureStorageUnavailableException]
     * if secure storage is unavailable (KeyStore failure / store init failure).
     *
     * Every setter that persists credentials MUST go through this helper so that failures
     * are surfaced to callers instead of being silently dropped.
     */
    internal fun requireSecurePrefs(op: String): SharedPreferences {
        awaitInit()
        return prefs ?: throw LRRSecureStorageUnavailableException(
            "Secure credential store unavailable; cannot perform $op"
        )
    }

    /** Plain prefs readable without waiting for (or depending on) the keystore. */
    internal fun fastPlainPrefs(): SharedPreferences? =
        plainPrefs ?: appContext?.getSharedPreferences(PLAIN_PREF_NAME, Context.MODE_PRIVATE)

    /**
     * Inject a [SharedPreferences] instance for unit-testing environments where
     * the KeyStore-backed store is unavailable (e.g., Robolectric without a real KeyStore).
     * Must NOT be called from production code.
     */
    @JvmStatic
    internal fun initializeForTesting(testPrefs: SharedPreferences) {
        prefs = testPrefs
        plainPrefs = testPrefs
        profileKeyCache.clear()
        needsReauthentication = false
        activeProfileId = testPrefs.getLong(KEY_ACTIVE_PROFILE_ID, 0L)
        clockMillis = { SystemClock.elapsedRealtime() }
        openInitGate()
    }

    /**
     * Simulate secure-storage unavailability for unit tests. Clears [prefs] so that
     * every setter throws [LRRSecureStorageUnavailableException] on the next call.
     * Must NOT be called from production code.
     */
    @JvmStatic
    internal fun simulateStorageUnavailableForTesting() {
        prefs = null
        profileKeyCache.clear()
        activeProfileId = 0L
        // Keep plainPrefs alive — lockout state must survive KeyStore failures.
    }

    /** False when the secure credential store could not be opened this process. */
    @JvmStatic
    fun isSecureStorageAvailable(): Boolean {
        awaitInit()
        return prefs != null
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
        val opened = prefs
        return when {
            opened != null -> SecureStorageState.AVAILABLE
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
     * Drop the encrypted store (pattern, API keys, server URL), its master
     * key and the legacy store. The first half of the app lock's last-resort
     * reset (AppLockCredentials.resetAppLockAndCredentials), which then
     * clears the lock state.
     */
    internal fun wipe(context: Context) {
        awaitInit()
        profileKeyCache.clear()
        prefs = null
        val ctx = context.applicationContext
        ctx.deleteSharedPreferences(SECURE_PREF_NAME)
        try {
            KeystoreValueCipher(KEYSTORE_ALIAS_SECURE_PREFS).deleteKey()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete the credential key", e)
        }
        deleteLegacyStore(ctx)
    }

    /**
     * Clear all stored credentials: the session, every profile key and the
     * app lock.
     */
    @JvmStatic
    fun clear() {
        awaitInit()
        profileKeyCache.clear()
        prefs?.edit { clear() }
        plainPrefs?.edit {
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
        activeProfileId = 0
        needsReauthentication = false
    }
}
