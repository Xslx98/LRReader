package com.lanraragi.framework.lib.glgallery

/**
 * The calls [GalleryView] posts from the UI thread (touch events, setters) for
 * the render thread to run at its next frame.
 *
 * Every drag, fling or pinch event lands here, so posting and draining must not
 * allocate: a call is a method id plus up to [MAX_ARGS] arguments kept as raw
 * int bits in primitive arrays (no boxed Float/Integer, no vararg array, no
 * collection copy). Two batches swap roles under the lock: the UI thread fills
 * the pending one while the render thread reads the other outside the lock, as
 * the old list copy did. The arrays only grow (doubling) when more calls arrive
 * between two frames than they have ever held.
 */
internal class RenderCallQueue {

    private class Batch {
        var size = 0
        var methods = IntArray(INITIAL_CAPACITY)
        var args = IntArray(INITIAL_CAPACITY * MAX_ARGS)

        /** Appends [method] and returns the index of its first argument slot. */
        fun add(method: Int): Int {
            if (size == methods.size) {
                methods = methods.copyOf(size * 2)
                args = args.copyOf(size * 2 * MAX_ARGS)
            }
            methods[size] = method
            return size++ * MAX_ARGS
        }
    }

    private var pending = Batch()
    private var draining = Batch()

    @Synchronized
    fun post(method: Int) {
        pending.add(method)
    }

    @Synchronized
    fun post(method: Int, a: Int) {
        val batch = pending
        val base = batch.add(method)
        batch.args[base] = a
    }

    @Synchronized
    fun post(method: Int, a: Int, b: Float) {
        val batch = pending
        val base = batch.add(method)
        val args = batch.args
        args[base] = a
        args[base + 1] = b.toRawBits()
    }

    @Synchronized
    fun post(method: Int, a: Float, b: Float) {
        val batch = pending
        val base = batch.add(method)
        val args = batch.args
        args[base] = a.toRawBits()
        args[base + 1] = b.toRawBits()
    }

    @Synchronized
    fun post(method: Int, a: Float, b: Float, c: Float) {
        val batch = pending
        val base = batch.add(method)
        val args = batch.args
        args[base] = a.toRawBits()
        args[base + 1] = b.toRawBits()
        args[base + 2] = c.toRawBits()
    }

    @Suppress("LongParameterList") // a scroll event carries six floats
    @Synchronized
    fun post(method: Int, a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) {
        val batch = pending
        val base = batch.add(method)
        val args = batch.args
        args[base] = a.toRawBits()
        args[base + 1] = b.toRawBits()
        args[base + 2] = c.toRawBits()
        args[base + 3] = d.toRawBits()
        args[base + 4] = e.toRawBits()
        args[base + 5] = f.toRawBits()
    }

    /**
     * Render thread: takes every call posted so far as the batch to run, and
     * returns how many there are (0: nothing to run). Calls posted while the
     * batch runs, from any thread, wait for the next [beginDrain]. Pair with
     * [endDrain]; not re-entrant (a call run from the batch must not drain).
     */
    @Synchronized
    fun beginDrain(): Int {
        val posted = pending
        if (posted.size == 0) {
            return 0
        }
        pending = draining
        draining = posted
        return posted.size
    }

    /** The method id of call [index] in the batch [beginDrain] took. */
    fun method(index: Int): Int = draining.methods[index]

    /** Argument [slot] of call [index] in the drained batch, posted as an Int. */
    fun intArg(index: Int, slot: Int): Int = draining.args[index * MAX_ARGS + slot]

    /** Argument [slot] of call [index] in the drained batch, posted as a Float. */
    fun floatArg(index: Int, slot: Int): Float = Float.fromBits(draining.args[index * MAX_ARGS + slot])

    /** Render thread: the drained batch has run; its arrays are reused for later posts. */
    @Synchronized
    fun endDrain() {
        draining.size = 0
    }

    private companion object {
        const val MAX_ARGS = 6
        const val INITIAL_CAPACITY = 16
    }
}
