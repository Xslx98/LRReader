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

/**
 * Setting a new pattern takes two draws (audit 2026-10-04, ruling R21): a slip
 * on the first draw must not lock the user out. Clearing needs no confirmation.
 * The first draw lives only in memory; a recreated screen starts over.
 */
class PatternConfirmFlow {

    sealed interface Step {
        /** Blank or single dot: clear the protection now. */
        data object Clear : Step
        data object TooShort : Step
        /** First draw accepted; ask for the same pattern again. */
        data object ConfirmNext : Step
        /** Second draw differs; start over from the first draw. */
        data object Mismatch : Step
        data class Save(val pattern: String) : Step
    }

    private var firstDraw: String? = null

    val awaitingConfirmation: Boolean get() = firstDraw != null

    fun onSet(cellCount: Int, pattern: String): Step {
        val first = firstDraw
        if (first != null) {
            firstDraw = null
            return if (pattern == first) Step.Save(pattern) else Step.Mismatch
        }
        return when (PatternRules.decide(cellCount)) {
            PatternRules.Decision.CLEAR -> Step.Clear
            PatternRules.Decision.TOO_SHORT -> Step.TooShort
            PatternRules.Decision.SAVE -> {
                firstDraw = pattern
                Step.ConfirmNext
            }
        }
    }
}
