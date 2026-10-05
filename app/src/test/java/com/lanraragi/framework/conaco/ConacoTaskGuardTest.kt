package com.lanraragi.framework.conaco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Audit 2026-10-04 C34 / STAB-12: a failing task body completes as a miss, not a process crash. */
class ConacoTaskGuardTest {

    @Test
    fun runtimeException_becomesNull() {
        assertNull(ConacoTask.guardWork<String> { throw SecurityException("SAF grant revoked") })
        assertNull(ConacoTask.guardWork<String> { throw IllegalStateException("cache closed") })
    }

    @Test
    fun value_passesThrough() {
        assertEquals("v", ConacoTask.guardWork { "v" })
    }

    @Test(expected = OutOfMemoryError::class)
    fun fatalErrors_stillPropagate() {
        ConacoTask.guardWork<String> { throw OutOfMemoryError("fatal") }
    }
}
