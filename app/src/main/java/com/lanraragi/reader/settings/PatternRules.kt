package com.lanraragi.reader.settings

/**
 * What a drawn app-lock pattern means when the user taps "Set" (audit C48 /
 * SEC-13, ruling R12). A blank or single-dot pattern clears the protection,
 * as the screen's tip says; anything shorter than [MIN_CELLS] is refused
 * (a 2-dot pattern had only a few dozen combinations). Patterns saved
 * before this rule keep working: only setting a new one is checked.
 */
object PatternRules {

    const val MIN_CELLS = 4

    enum class Decision { CLEAR, TOO_SHORT, SAVE }

    fun decide(cellCount: Int): Decision = when {
        cellCount <= 1 -> Decision.CLEAR
        cellCount < MIN_CELLS -> Decision.TOO_SHORT
        else -> Decision.SAVE
    }
}
