package com.lanraragi.reader.settings

import com.lanraragi.reader.settings.PatternConfirmFlow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ruling R21: a new pattern is saved only after the same pattern is drawn twice. */
class PatternConfirmFlowTest {

    private val flow = PatternConfirmFlow()

    @Test
    fun the_same_pattern_twice_saves_it() {
        assertEquals(Step.ConfirmNext, flow.onSet(4, "0124"))
        assertTrue(flow.awaitingConfirmation)
        assertEquals(Step.Save("0124"), flow.onSet(4, "0124"))
        assertFalse(flow.awaitingConfirmation)
    }

    @Test
    fun a_different_second_draw_starts_over() {
        flow.onSet(4, "0124")
        assertEquals(Step.Mismatch, flow.onSet(4, "0125"))
        assertFalse(flow.awaitingConfirmation)
        // The next draw is a first draw again, never a save.
        assertEquals(Step.ConfirmNext, flow.onSet(4, "0125"))
    }

    @Test
    fun a_blank_second_draw_is_a_mismatch_not_a_clear() {
        flow.onSet(4, "0124")
        assertEquals(Step.Mismatch, flow.onSet(0, ""))
    }

    @Test
    fun clearing_and_short_patterns_need_no_confirmation() {
        assertEquals(Step.Clear, flow.onSet(0, ""))
        assertEquals(Step.TooShort, flow.onSet(3, "012"))
        assertFalse(flow.awaitingConfirmation)
    }
}
