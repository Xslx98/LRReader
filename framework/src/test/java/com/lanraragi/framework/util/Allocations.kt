package com.lanraragi.framework.util

import java.lang.reflect.Method

/**
 * Heap bytes the current thread allocates while [block] runs, from HotSpot's
 * per-thread counter (`com.sun.management.ThreadMXBean`). Unit tests compile
 * against android.jar, which has no java.lang.management, so the counter is
 * read by reflection; the bytes one read itself allocates (boxed result, empty
 * argument array) are measured right before and subtracted.
 *
 * Inline, so the measured block costs no lambda object. Run the block once
 * before measuring (class loading, lazy init, pool growth) and keep loops
 * short (hundreds of iterations): C2 escape analysis can only hide an
 * allocation, never invent one, so a guard measured before C2 compiles the
 * loop errs on the side of catching a regression.
 */
inline fun allocatedBytes(block: () -> Unit): Long {
    val overhead = ThreadAllocationCounter.readCost()
    val before = ThreadAllocationCounter.read()
    block()
    val after = ThreadAllocationCounter.read()
    return after - before - overhead
}

/**
 * The fewest bytes any of [runs] runs of [block] allocated. A test JVM
 * (Robolectric, JIT) sometimes allocates on the test thread in the middle of a
 * run; an allocation the code under test makes on every call shows up in every
 * run, so the minimum still catches it while ignoring that noise.
 */
inline fun minAllocatedBytes(runs: Int, block: () -> Unit): Long {
    var least = Long.MAX_VALUE
    repeat(runs) {
        least = minOf(least, allocatedBytes(block))
    }
    return least
}

object ThreadAllocationCounter {
    private val bean: Any
    private val counter: Method

    init {
        val factory = Class.forName("java.lang.management.ManagementFactory")
        bean = factory.getMethod("getThreadMXBean").invoke(null)
        counter = Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes")
    }

    fun read(): Long = counter.invoke(bean) as Long

    /** What one [read] allocates between its own sample and the next one's. */
    fun readCost(): Long {
        var cost = Long.MAX_VALUE
        repeat(5) {
            val a = read()
            val b = read()
            cost = minOf(cost, b - a)
        }
        return cost
    }
}
