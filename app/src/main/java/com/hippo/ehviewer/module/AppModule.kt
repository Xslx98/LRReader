package com.hippo.ehviewer.module

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.Crash
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.Settings
import com.hippo.lib.yorozuya.IntIdGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.lang.ref.WeakReference

/**
 * Application-level utilities: global object registry and temp cache.
 * Extracted from EhApplication to reduce its responsibility scope.
 *
 * Also hosts the early-boot [bootScope]/[bootCEH] used by [EhApplication.onCreate]
 * BEFORE [com.hippo.ehviewer.ServiceRegistry] has been initialized. Code that runs
 * after `ServiceRegistry.initialize()` should prefer
 * `ServiceRegistry.coroutineModule.ioScope` instead.
 */
class AppModule(private val context: Context) : IAppModule {

    /** Returns the application context. Use instead of EhApplication.getInstance(). */
    override fun getContext(): Context = context

    private val idGenerator = IntIdGenerator()
    private val globalStuffMap = HashMap<Int, WeakReference<Any>>()
    private val tempCacheMap = HashMap<String, Any>()

    override fun initialize() {
        idGenerator.setNextId(Settings.getInt(KEY_GLOBAL_STUFF_NEXT_ID, 0))
    }

    // --- Global Stuff Registry ---

    override fun putGlobalStuff(o: Any): Int {
        val id = idGenerator.nextId()
        globalStuffMap[id] = WeakReference(o)
        Settings.putInt(KEY_GLOBAL_STUFF_NEXT_ID, idGenerator.nextId())
        return id
    }

    override fun containGlobalStuff(id: Int): Boolean = globalStuffMap[id]?.get() != null

    override fun getGlobalStuff(id: Int): Any? = globalStuffMap[id]?.get()

    override fun removeGlobalStuff(id: Int): Any? = globalStuffMap.remove(id)?.get()

    override fun removeGlobalStuff(o: Any) {
        globalStuffMap.values.removeAll { it.get() == o || it.get() == null }
    }

    // --- Temp Cache ---

    override fun putTempCache(key: String, o: Any): String {
        tempCacheMap[key] = o
        return key
    }

    override fun containTempCache(key: String): Boolean = tempCacheMap.containsKey(key)

    override fun getTempCache(key: String): Any? = tempCacheMap[key]

    override fun removeTempCache(key: String): Any? = tempCacheMap.remove(key)

    companion object {
        private const val KEY_GLOBAL_STUFF_NEXT_ID = "global_stuff_next_id"
        private const val BOOT_TAG = "AppModule.bootScope"

        @JvmStatic
        fun getDeveloperEmail(): String = "xiaojieonly\$foxmail.com".replace('$', '@')

        /**
         * Boot-time [CoroutineExceptionHandler] used by [bootScope]. Reports uncaught
         * exceptions to [Crash.saveCrashLog] and [Analytics.recordException], with
         * try/catch protection so a failure inside the handler can never crash the app.
         *
         * Production sites use [bootCEH]; tests build their own via [createBootCEH] with
         * injectable function references because [Crash] / [Analytics] are Kotlin
         * `object` singletons that resist mocking.
         */
        @JvmStatic
        val bootCEH: CoroutineExceptionHandler = createBootCEH(
            saveCrashLog = { t ->
                val ctx: Context? = try {
                    EhApplication.instance
                } catch (e: Throwable) {
                    Log.w(BOOT_TAG, "Retrieve EhApplication instance for crash log", e)
                    null
                }
                if (ctx != null) Crash.saveCrashLog(ctx, t)
            },
            recordException = { t -> Analytics.recordException(t) },
        )

        /**
         * Long-lived [CoroutineScope] for boot-time work that must run before
         * [com.hippo.ehviewer.ServiceRegistry] is initialized (and therefore cannot
         * use `ServiceRegistry.coroutineModule.ioScope`). Backed by [SupervisorJob]
         * so a single failure does not cancel sibling tasks, and routes uncaught
         * exceptions through [bootCEH].
         *
         * Lives the entire app lifetime — never cancelled. After ServiceRegistry has
         * been initialized, new code should prefer the IO scope on the coroutine
         * module instead of this scope.
         */
        @JvmStatic
        val bootScope: CoroutineScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + bootCEH,
        )

        /**
         * `CompletableDeferred` for the active server profile id, completed by the
         * profile-loader coroutine launched from [EhApplication.onCreate]. Downstream
         * code that needs to wait for the active profile to be resolved can `await()`
         * this instead of racing the loader.
         *
         * Completes with `null` if no profile is active or if loading fails (the
         * loader uses try/finally so awaiters never hang).
         */
        @JvmStatic
        val activeProfileIdDeferred: CompletableDeferred<Long?> = CompletableDeferred()

        /**
         * Build a [CoroutineExceptionHandler] from injectable function references.
         * Both callbacks are wrapped in try/catch so a misbehaving handler cannot
         * itself crash the process (a death loop while reporting another crash).
         *
         * Public so unit tests can substitute mock function references in place of
         * the real [Crash] / [Analytics] singletons.
         */
        @JvmStatic
        fun createBootCEH(
            saveCrashLog: (Throwable) -> Unit,
            recordException: (Throwable) -> Unit,
        ): CoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
            try {
                saveCrashLog(throwable)
            } catch (inner: Throwable) {
                Log.e(BOOT_TAG, "saveCrashLog failed inside bootCEH", inner)
            }
            try {
                recordException(throwable)
            } catch (inner: Throwable) {
                Log.e(BOOT_TAG, "recordException failed inside bootCEH", inner)
            }
        }
    }
}
