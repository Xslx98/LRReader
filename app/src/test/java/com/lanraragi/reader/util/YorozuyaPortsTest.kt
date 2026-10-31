package com.lanraragi.reader.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The yorozuya helpers ported to reader.util (2.4 P3) keep their results:
 * the GL reader, scenes and the download naming depend on the exact values.
 */
class YorozuyaPortsTest {

    @Test
    fun clamp_checks_max_before_min_like_the_original() {
        assertEquals(5, MathUtils.clamp(7, 0, 5))
        assertEquals(0, MathUtils.clamp(-3, 0, 5))
        // min > max: the max check wins (coerceIn would throw here).
        assertEquals(1, MathUtils.clamp(3, 5, 1))
        assertEquals(1f, MathUtils.clamp(3f, 5f, 1f))
        assertEquals(1L, MathUtils.clamp(3L, 5L, 1L))
    }

    @Test
    fun lerp_and_delerp_round_trip_and_delerp_of_an_empty_range_is_one() {
        assertEquals(15, MathUtils.lerp(10, 20, 0.5f))
        assertEquals(12.5f, MathUtils.lerp(10f, 20f, 0.25f))
        assertEquals(0.25f, MathUtils.delerp(10, 50, 20))
        assertEquals(0.5f, MathUtils.delerp(0f, 4f, 2f))
        assertEquals(1f, MathUtils.delerp(7, 7, 3))
        assertEquals(1f, MathUtils.delerp(7f, 7f, 3f))
    }

    @Test
    fun nextPowerOf2_and_positiveModulo() {
        assertEquals(1, MathUtils.nextPowerOf2(1))
        assertEquals(64, MathUtils.nextPowerOf2(64))
        assertEquals(128, MathUtils.nextPowerOf2(65))
        assertThrows(IllegalArgumentException::class.java) { MathUtils.nextPowerOf2(0) }
        assertEquals(350f, MathUtils.positiveModulo(-10f, 360f))
        assertEquals(10f, MathUtils.positiveModulo(370f, 360f))
    }

    @Test
    fun sanitizeFilename_strips_forbidden_characters_and_edge_spaces() {
        assertEquals("ab cd", FileUtils.sanitizeFilename("　 a\\b:*?\"<>| c/d\t"))
    }

    @Test
    fun sanitizeFilename_cuts_at_255_utf8_bytes_without_splitting_a_character() {
        // 85 three-byte CJK characters = 255 bytes: kept whole.
        val exact = "漢".repeat(85)
        assertEquals(exact, FileUtils.sanitizeFilename(exact))
        // One more would be 258 bytes: the cut falls before it.
        assertEquals(exact, FileUtils.sanitizeFilename(exact + "漢"))
        assertEquals("a".repeat(255), FileUtils.sanitizeFilename("a".repeat(300)))
    }

    @Test
    fun humanReadableByteCount_and_extension() {
        assertEquals("999 B", FileUtils.humanReadableByteCount(999, false))
        assertEquals("1.5 KiB", FileUtils.humanReadableByteCount(1536, false))
        assertEquals("1.5 kB", FileUtils.humanReadableByteCount(1500, true))
        assertEquals("png", FileUtils.getExtensionFromFilename("0001.png"))
        assertEquals(null, FileUtils.getExtensionFromFilename("README"))
    }

    @Test
    fun intList_inserts_removes_and_survives_a_saved_state_round_trip() {
        val list = IntList()
        repeat(20) { list.add(it) }
        list.add(0, -1)
        assertEquals(21, list.size())
        assertEquals(-1, list.get(0))
        assertEquals(0, list.removeAt(1))
        assertEquals(5, list.set(5, 50))

        val restored = IntList.of(list.toArray())
        assertArrayEquals(list.toArray(), restored.toArray())
        assertEquals(0, IntList.of(null).size())
        assertThrows(IndexOutOfBoundsException::class.java) { list.get(list.size()) }
    }

    @Test
    fun intIdGenerator_never_hands_out_the_invalid_id() {
        val ids = IntIdGenerator()
        ids.setNextId(-2)
        assertEquals(-2, ids.nextId())
        assertEquals(0, ids.nextId())
        assertThrows(IllegalStateException::class.java) { ids.setNextId(IntIdGenerator.INVALID_ID) }
    }
}
