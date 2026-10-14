package com.lanraragi.framework.lib.glview.image

/**
 * A thread-safe LIFO free list that keeps at most [capacity] items. Pooled
 * GL tiles keep their texture allocated, so the pool size is VRAM held after
 * the pages that used them are gone (audit 2026-10-13 PERF-02).
 */
class BoundedFreeList<T : Any>(private val capacity: Int) {

    private val items = ArrayDeque<T>()

    /** The most recently offered item, or null when empty. */
    @Synchronized
    fun poll(): T? = items.removeLastOrNull()

    /** Keeps [item] for reuse; false when full, and the caller must release it. */
    @Synchronized
    fun offer(item: T): Boolean {
        if (items.size >= capacity) return false
        items.addLast(item)
        return true
    }

    /** Removes and returns every pooled item, for the caller to release. */
    @Synchronized
    fun drain(): List<T> = items.toList().also { items.clear() }

    @get:Synchronized
    val size: Int get() = items.size
}
