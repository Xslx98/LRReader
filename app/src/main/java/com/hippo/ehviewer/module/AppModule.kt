package com.hippo.ehviewer.module

import android.content.Context
import com.hippo.ehviewer.Settings
import com.hippo.lib.yorozuya.IntIdGenerator
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Application-level utilities: global object registry, temp cache, and thread pool.
 * Extracted from EhApplication to reduce its responsibility scope.
 */
class AppModule(private val context: Context) : IAppModule {

    /** Returns the application context. Use instead of EhApplication.getInstance(). */
    override fun getContext(): Context = context

    private val idGenerator = IntIdGenerator()
    private val globalStuffMap = HashMap<Int, WeakReference<Any>>()
    private val tempCacheMap = HashMap<String, Any>()
    override val executorService: ExecutorService = Executors.newFixedThreadPool(4)

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

        @JvmStatic
        fun getDeveloperEmail(): String = "xiaojieonly\$foxmail.com".replace('$', '@')
    }
}
