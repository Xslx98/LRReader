package com.lanraragi.reader.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Audit C48 / SEC-13 (ruling R12): new patterns need at least 4 dots. */
class PatternRulesTest {

    @Test
    fun blank_or_single_dot_clears() {
        assertEquals(PatternRules.Decision.CLEAR, PatternRules.decide(0))
        assertEquals(PatternRules.Decision.CLEAR, PatternRules.decide(1))
    }

    @Test
    fun two_or_three_dots_are_refused() {
        assertEquals(PatternRules.Decision.TOO_SHORT, PatternRules.decide(2))
        assertEquals(PatternRules.Decision.TOO_SHORT, PatternRules.decide(3))
    }

    @Test
    fun four_dots_and_more_are_saved() {
        assertEquals(PatternRules.Decision.SAVE, PatternRules.decide(4))
        assertEquals(PatternRules.Decision.SAVE, PatternRules.decide(9))
    }
}
