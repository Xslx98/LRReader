package com.lanraragi.reader.tankoubon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TankTitleSortKeyTest {

    // Through the production sort (id == title), so every table below guards
    // the order users actually get, including the title/id tie-break.
    private fun sorted(vararg titles: String) = TankMemberOrderOps.sortByTitle(titles.toList()) { it }

    private fun episode(title: String) = TankTitleSortKey.parse(title).episode

    @Test
    fun rangesTakeTheirStart() {
        assertEquals(
            listOf("作品 1-5话", "作品 6~10话", "作品 11至15话", "作品 16–20话"),
            sorted("作品 11至15话", "作品 1-5话", "作品 16–20话", "作品 6~10话"),
        )
    }

    @Test
    fun chineseAndArabicEpisodesShareOneKey() {
        assertEquals(1, episode("第一回"))
        assertEquals(1, episode("第1话"))
        assertEquals(1, episode("第01话"))
        assertEquals(23, episode("第二十三章"))
        assertEquals(105, episode("第一百零五集"))
        assertEquals(
            listOf("第一回", "第2话", "第三章", "第4集", "第五期"),
            sorted("第4集", "第五期", "第2话", "第三章", "第一回"),
        )
    }

    @Test
    fun episodeNumbersCompareNumericallyNotLexically() {
        assertEquals(
            listOf("第1话", "第11话", "第111话"),
            sorted("第111话", "第11话", "第1话"),
        )
    }

    @Test
    fun unitWithoutMarkerAndMarkerWithoutUnit() {
        assertEquals(7, episode("7话 タイトル"))
        assertEquals(3, episode("第03"))
    }

    @Test
    fun latinMarkers() {
        assertEquals(3, episode("Title Vol.3"))
        assertEquals(12, episode("Title Ch 12"))
        assertEquals(4, episode("Chapter 4: Foo"))
        assertEquals(9, episode("EP9"))
        assertEquals(2, episode("Title #2"))
        assertEquals(5, episode("Episode 5"))
        assertEquals(
            listOf("T Ch.1", "T ch 2", "T #3", "T Vol.10"),
            sorted("T Vol.10", "T #3", "T ch 2", "T Ch.1"),
        )
    }

    @Test
    fun fullwidthIsNormalized() {
        assertEquals(12, episode("第１２话"))
        assertEquals(3, episode("Ｖｏｌ．３"))
        assertEquals(4, episode("＃４"))
    }

    @Test
    fun subOrderWithinAnEpisode() {
        assertEquals(
            listOf("第3话 上", "第3话（中）", "第3话 下", "第4话"),
            sorted("第4话", "第3话 下", "第3话 上", "第3话（中）"),
        )
        assertEquals(listOf("第2话 前篇", "第2话 后篇"), sorted("第2话 后篇", "第2话 前篇"))
        assertEquals(listOf("第2話 前編", "第2話 後編"), sorted("第2話 後編", "第2話 前編"))
        assertEquals(listOf("上篇", "中篇", "下篇"), sorted("下篇", "上篇", "中篇"))
    }

    @Test
    fun subOrderIgnoresOrdinaryCharacters() {
        // 地下 / 上海 are words, not markers.
        assertEquals(0, TankTitleSortKey.parse("第1话 地下室").subOrder)
        assertEquals(0, TankTitleSortKey.parse("第1话 上海").subOrder)
    }

    @Test
    fun extrasSortAfterEveryRegularMember() {
        assertEquals(
            listOf("第1话", "第2话", "第10话", "番外 第1话", "番外篇 2"),
            sorted("番外篇 2", "第10话", "番外 第1话", "第2话", "第1话"),
        )
    }

    @Test
    fun firstNumberFallbackWhenNoMarker() {
        assertNull(episode("Title 03"))
        assertEquals(3, TankTitleSortKey.parse("Title 03").firstNumber)
        assertEquals(listOf("Title 01", "Title 2", "Title 10"), sorted("Title 10", "Title 2", "Title 01"))
    }

    @Test
    fun markedAndUnmarkedNumbersShareThePrimaryAxis() {
        assertEquals(listOf("第1话", "Title 2", "第3话"), sorted("第3话", "Title 2", "第1话"))
    }

    @Test
    fun titlesWithoutNumbersFollowNumberedOnesInNaturalOrder() {
        assertEquals(listOf("第1话", "Alpha", "beta", "Gamma"), sorted("Gamma", "beta", "第1话", "Alpha"))
    }

    @Test
    fun naturalCompareIsNumericAndCaseInsensitive() {
        assertTrue(TankTitleSortKey.compareNatural("a2", "a10") < 0)
        assertEquals(0, TankTitleSortKey.compareNatural("a02", "a2"))
        assertTrue(TankTitleSortKey.compareNatural("abc", "abd") < 0)
        assertEquals(0, TankTitleSortKey.parse("ABC").natural.compareTo(TankTitleSortKey.parse("abc").natural))
    }

    @Test
    fun equalKeysAreOrderedByTitleForDeterminism() {
        assertEquals(sorted("第1话", "第一回"), sorted("第一回", "第1话"))
    }
}
