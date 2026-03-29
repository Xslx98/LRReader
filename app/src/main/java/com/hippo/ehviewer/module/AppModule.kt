package com.hippo.ehviewer.module

import androidx.annotation.NonNull
import com.hippo.ehviewer.Settings
import com.hippo.lib.yorozuya.IntIdGenerator
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Application-level utilities: global object registry, temp cache, and thread pool.
 * Extracted from EhApplication to reduce its responsibility scope.
 */
class AppModule {

    private val idGenerator = IntIdGenerator()
    private val globalStuffMap = HashMap<Int, Any>()
    private val tempCacheMap = HashMap<String, Any>()
    val executorService: ExecutorService = Executors.newCachedThreadPool()

    fun initialize() {
        idGenerator.setNextId(Settings.getInt(KEY_GLOBAL_STUFF_NEXT_ID, 0))
    }

    // --- Global Stuff Registry ---

    fun putGlobalStuff(@NonNull o: Any): Int {
        val id = idGenerator.nextId()
        globalStuffMap[id] = o
        Settings.putInt(KEY_GLOBAL_STUFF_NEXT_ID, idGenerator.nextId())
        return id
    }

    fun containGlobalStuff(id: Int): Boolean = globalStuffMap.containsKey(id)

    fun getGlobalStuff(id: Int): Any? = globalStuffMap[id]

    fun removeGlobalStuff(id: Int): Any? = globalStuffMap.remove(id)

    fun removeGlobalStuff(o: Any) {
        globalStuffMap.values.removeAll(Collections.singleton(o))
    }

    // --- Temp Cache ---

    fun putTempCache(@NonNull key: String, @NonNull o: Any): String {
        tempCacheMap[key] = o
        return key
    }

    fun containTempCache(@NonNull key: String): Boolean = tempCacheMap.containsKey(key)

    fun getTempCache(@NonNull key: String): Any? = tempCacheMap[key]

    fun removeTempCache(@NonNull key: String): Any? = tempCacheMap.remove(key)

    companion object {
        private const val KEY_GLOBAL_STUFF_NEXT_ID = "global_stuff_next_id"

        @JvmStatic
        fun getDeveloperEmail(): String = "xiaojieonly\$foxmail.com".replace('$', '@')
    }
}
