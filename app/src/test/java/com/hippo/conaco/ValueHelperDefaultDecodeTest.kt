package com.hippo.conaco

import com.hippo.streampipe.InputStreamPipe
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The 4-arg decode(pipe, hardware, targetWidth, targetHeight) is a default
 * method so legacy ValueHelper implementations keep compiling; it must
 * delegate to the 2-arg decode when not overridden.
 */
class ValueHelperDefaultDecodeTest {

    private class RecordingHelper : ValueHelper<String> {
        var twoArgCalls = 0
        var lastHardware: Boolean? = null

        override fun decode(isPipe: InputStreamPipe): String = "one-arg"

        override fun decode(isPipe: InputStreamPipe, hardware: Boolean): String {
            twoArgCalls++
            lastHardware = hardware
            return "two-arg"
        }

        override fun sizeOf(key: String, value: String): Int = value.length
        override fun onAddToMemoryCache(oldValue: String) = Unit
        override fun onRemoveFromMemoryCache(key: String, oldValue: String) = Unit
        override fun useMemoryCache(key: String, holder: String?): Boolean = true
    }

    private object EmptyPipe : InputStreamPipe {
        override fun obtain() = Unit
        override fun release() = Unit
        override fun open(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun close() = Unit
    }

    @Test
    fun `default four-arg decode delegates to two-arg decode`() {
        val helper = RecordingHelper()
        val result = helper.decode(EmptyPipe, false, 336, 470)
        assertEquals("two-arg", result)
        assertEquals(1, helper.twoArgCalls)
        assertSame(false, helper.lastHardware)
    }
}
