package com.lanraragi.reader.util

import android.content.ComponentCallbacks2
import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A cache that gives memory back when the system asks (audit 2026-10-04 C12 /
 * PERF-02). Before this only Conaco and the detail LRU reacted to
 * onTrimMemory; the 256 MB reader cache, page thumbnails and the decoded
 * warm-up slot stayed resident while the app sat in the background.
 */
interface MemoryTrimmable {
    /** Main thread. [action] is never [MemoryTrim.Action.NONE]. */
    fun onTrimMemory(action: MemoryTrim.Action)
}

object MemoryTrim {

    private const val TAG = "MemoryTrim"

    enum class Action {
        NONE,

        /** Keep only the most recently used part (UI hidden, backgrounded, running low). */
        TRIM,

        /** Drop everything (about to be killed, or critical while in the foreground). */
        CLEAR,
    }

    private val entries = CopyOnWriteArrayList<WeakReference<MemoryTrimmable>>()

    @JvmStatic
    fun actionFor(level: Int): Action = when {
        level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> Action.CLEAR
        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> Action.TRIM
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> Action.CLEAR
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> Action.TRIM
        else -> Action.NONE
    }

    /** Held weakly: a provider that is never unregistered does not leak. */
    @JvmStatic
    fun register(trimmable: MemoryTrimmable) {
        if (entries.none { it.get() === trimmable }) entries.add(WeakReference(trimmable))
    }

    @JvmStatic
    fun unregister(trimmable: MemoryTrimmable) {
        entries.removeAll { it.get() == null || it.get() === trimmable }
    }

    /** From Application.onTrimMemory (main thread). */
    @JvmStatic
    fun dispatch(level: Int) {
        val action = actionFor(level)
        if (action == Action.NONE) return
        entries.removeAll { it.get() == null }
        for (ref in entries) {
            val trimmable = ref.get() ?: continue
            try {
                trimmable.onTrimMemory(action)
            } catch (e: RuntimeException) {
                Log.e(TAG, "onTrimMemory failed in ${trimmable.javaClass.name}", e)
            }
        }
    }

    /**
     * Reader page cache budget (PERF-02 / FW-4): a twelfth of RAM, 64..256 MB,
     * and 96 MB on low-RAM devices (2 GB -> ~170 MB, 3 GB and up -> 256 MB). It
     * was totalRAM/6, which gave every phone with 1.5 GB or more the full 256 MB.
     */
    @JvmStatic
    fun readerCacheBytes(totalMemBytes: Long, lowRam: Boolean): Int {
        if (lowRam) return LOW_RAM_READER_CACHE
        return (totalMemBytes / RAM_FRACTION).coerceIn(MIN_READER_CACHE.toLong(), MAX_READER_CACHE.toLong()).toInt()
    }

    /** ActivityManager.isLowRamDevice for the running app; false before the app exists (tests). */
    @JvmStatic
    fun isLowRamDevice(): Boolean = try {
        com.lanraragi.reader.LRReaderApplication.instance
            .getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice == true
    } catch (e: UninitializedPropertyAccessException) {
        Log.w(TAG, "No application yet; assuming a normal-RAM device", e)
        false
    }

    private const val RAM_FRACTION = 12
    private const val MB = 1024 * 1024
    const val MIN_READER_CACHE = 64 * MB
    const val MAX_READER_CACHE = 256 * MB
    const val LOW_RAM_READER_CACHE = 96 * MB
}
