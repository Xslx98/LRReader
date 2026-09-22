package com.lanraragi.reader.tankoubon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [TankNameSuggester] (spec 2026-09-22 §9): the name box pre-fill for create-from-selection. */
class TankNameSuggesterTest {

    private fun suggest(vararg titles: String) = TankNameSuggester.suggest(titles.toList())

    // ---- episode markers stripped, common prefix kept ----

    @Test
    fun cjkEpisodeSeries() {
        assertEquals("小说", suggest("小说 第一回", "小说 第2话", "小说 第三章"))
        assertEquals("连载", suggest("连载 第1话", "连载 第11话", "连载 第111话"))
        assertEquals("合集", suggest("合集 1-5话", "合集 6~10话", "合集 11至15话"))
    }

    @Test
    fun latinEpisodeSeries() {
        assertEquals("Series", suggest("Series Vol.1", "Series Vol.2", "Series Vol.10"))
        assertEquals("Series", suggest("Series Ch 3", "Series #4", "Series EP5"))
    }

    @Test
    fun subOrderAndExtraMarkers() {
        assertEquals("长篇", suggest("长篇 第3话 上", "长篇 第3话 下", "长篇 第3话（中）"))
        assertEquals("长篇", suggest("长篇 第4话 前篇", "长篇 第4话 后篇"))
        // 番外 is a kind, not an ordinal: it stays, and the majority fallback picks it.
        assertEquals("番外", suggest("番外 第1话", "番外篇 2", "番外 第3话"))
    }

    @Test
    fun trailingBareNumbersAreEpisodes() {
        assertEquals("Extra", suggest("Extra 01", "Extra 2", "Extra 10"))
        assertEquals("Alpha", suggest("Alpha 1", "Alpha 2"))
        assertEquals("Alpha", suggest("Alpha １", "Alpha ２"))
    }

    @Test
    fun numbersInTheMiddleStay() {
        assertEquals("Series 2020", suggest("Series 2020 Vol.1", "Series 2020 Vol.2"))
    }

    // ---- brackets ----

    @Test
    fun leadingAndTrailingBracketBlocksAreStripped() {
        assertEquals("作品名", suggest("(C99) [作者] 作品名 第1话 (中文)", "(C99) [作者] 作品名 第2话 (中文)"))
        assertEquals("作品名", suggest("[汉化组] 作品名 [DL版]", "[汉化组] 作品名 第2话 [DL版]"))
    }

    @Test
    fun middleBracketBlocksParticipate() {
        assertEquals("作品名 (完全版)", suggest("作品名 (完全版) 上", "作品名 (完全版) 下"))
    }

    @Test
    fun allBracketTitlesFallBackToUnstripped() {
        assertEquals("[作者]", suggest("[作者]", "[作者]"))
    }

    // ---- prefix + suffix sandwich ----

    @Test
    fun commonPrefixAndSuffixAroundTheMarker() {
        assertEquals("作品名 完全版", suggest("作品名 第1话 完全版", "作品名 第2话 完全版"))
        assertEquals("作品名", suggest("作品名 后篇 (汉化组A)", "作品名 前篇 (汉化组B)"))
    }

    @Test
    fun residueSeparatorsAreCleaned() {
        assertEquals("作品名", suggest("作品名 - 第1话", "作品名 - 第2话"))
        assertEquals("作品名", suggest("作品名・上", "作品名・下"))
        assertEquals("作品名", suggest("作品名 ()", "作品名 第2话"))
    }

    // ---- fallback: majority tokens ----

    @Test
    fun noCommonPrefixFallsBackToMajorityTokens() {
        assertEquals("作者X", suggest("Alpha 作者X", "Beta 作者X", "Gamma 作者X"))
        assertEquals("Season", suggest("One Season 1", "Two Season 2", "Three Other 3"))
    }

    @Test
    fun majorityTokensIgnoreShortAndNumericOnes() {
        assertNull(suggest("A 1", "B 1", "C 1"))
    }

    // ---- edge cases ----

    @Test
    fun singleTitleIsItsStrippedForm() {
        assertEquals("小说", suggest("小说 第一回"))
        assertEquals("Alpha", suggest("Alpha"))
    }

    @Test
    fun emptyOrUnrelatedTitlesGiveNothing() {
        assertNull(suggest())
        assertNull(suggest("", "  "))
        assertNull(suggest("Alpha", "Gamma", "beta"))
        assertNull(suggest("第1话", "第2话"))
    }

    @Test
    fun oneCharacterResultsAreRejected() {
        assertNull(suggest("小 第1话", "小 第2话"))
    }
}
