package com.lanraragi.reader.tankoubon

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TankTagMerge] contract (spec 2026-09-22 §5): the tank's own tags are the
 * materialized union of member tags minus the excluded namespaces; adds
 * union in, removals follow rule 3 (hand-written tags survive), reset
 * recomputes, and tank tags in excluded namespaces are preserved verbatim.
 */
class TankTagMergeTest {

    // ---- split / join ----

    @Test
    fun split_trimsAndDropsEmptyEntries() {
        assertEquals(listOf("a:b", "c"), TankTagMerge.split(" a:b ,, c , "))
        assertEquals(emptyList<String>(), TankTagMerge.split(null))
        assertEquals(emptyList<String>(), TankTagMerge.split(""))
    }

    @Test
    fun join_usesLanraragiSeparator() {
        assertEquals("a:b, c", TankTagMerge.join(listOf("a:b", "c")))
        assertEquals("", TankTagMerge.join(emptyList()))
    }

    // ---- union (§5.1) ----

    @Test
    fun union_keepsMemberOrderDedupesCaseInsensitivelyKeepingFirstSpelling() {
        val union = TankTagMerge.union(
            listOf("artist:Foo, language:english", "artist:foo, parody:x", "Language:English, group:g"),
        )
        assertEquals(listOf("artist:Foo", "language:english", "parody:x", "group:g"), union)
    }

    @Test
    fun union_dropsExcludedNamespaces() {
        val union = TankTagMerge.union(
            listOf("artist:a, date_added:1, timestamp:2, source:s, rating:⭐⭐, misc"),
        )
        assertEquals(listOf("artist:a", "misc"), union)
    }

    @Test
    fun union_excludedNamespaceMatchIsCaseInsensitiveAndTrimmed() {
        assertEquals(listOf("x:y"), TankTagMerge.union(listOf("Date_Added:1, x:y, RATING : 3")))
    }

    // ---- add (§5.2 rows 1–2) ----

    @Test
    fun onAdd_isTankUnionNewMembersWithTankOrderFirst() {
        val result = TankTagMerge.onAdd(
            tankTags = "artist:a, rating:⭐⭐, hand:written",
            newMembersTags = listOf("artist:a, parody:p", "language:en, date_added:9"),
        )
        assertEquals("artist:a, rating:⭐⭐, hand:written, parody:p, language:en", result)
    }

    @Test
    fun onAdd_fromEmptyTankIsTheMembersUnion() {
        assertEquals("a:1, b:2", TankTagMerge.onAdd(null, listOf("a:1", "b:2, a:1")))
    }

    // ---- remove: rule 3 (§5.2 row 3) ----

    @Test
    fun onRemove_keepsHandWrittenTagsAndDropsTagsOnlyTheRemovedMemberHad() {
        val result = TankTagMerge.onRemove(
            tankTags = "artist:a, parody:p, hand:written, rating:⭐",
            membersBefore = listOf("artist:a", "parody:p, artist:a"),
            membersRemaining = listOf("artist:a"),
        )
        // manual = tank − ⋃(before) = hand:written (+ preserved rating)
        // new = ⋃(remaining) ∪ manual, tank order first
        assertEquals("artist:a, hand:written, rating:⭐", result)
    }

    @Test
    fun onRemove_tagSharedWithARemainingMemberSurvives() {
        val result = TankTagMerge.onRemove(
            tankTags = "artist:a, parody:p",
            membersBefore = listOf("parody:p", "parody:p, artist:a"),
            membersRemaining = listOf("parody:p, artist:a"),
        )
        assertEquals("artist:a, parody:p", result)
    }

    @Test
    fun onRemove_remainingMembersContributeTagsTheTankLacked() {
        val result = TankTagMerge.onRemove(
            tankTags = "artist:a",
            membersBefore = listOf("artist:a", "artist:a, new:tag"),
            membersRemaining = listOf("artist:a, new:tag"),
        )
        assertEquals("artist:a, new:tag", result)
    }

    @Test
    fun onRemove_lastMemberLeavesOnlyManualAndExcludedTags() {
        val result = TankTagMerge.onRemove(
            tankTags = "artist:a, hand:written, rating:⭐⭐",
            membersBefore = listOf("artist:a"),
            membersRemaining = emptyList(),
        )
        assertEquals("hand:written, rating:⭐⭐", result)
    }

    // ---- member tag edit (§5.2 row 4) = rule 3 with old/new member tags ----

    @Test
    fun onMemberTagsChanged_replacesTheMembersOldContributionAndKeepsManual() {
        val result = TankTagMerge.onMemberTagsChanged(
            tankTags = "artist:a, old:x, hand:written",
            membersBefore = listOf("artist:a, old:x", "artist:a"),
            membersAfter = listOf("artist:a, new:y", "artist:a"),
        )
        assertEquals("artist:a, hand:written, new:y", result)
    }

    // ---- reset (§5.4) ----

    @Test
    fun reset_dropsEverythingNonExcludedAndRecomputesTheUnion() {
        val result = TankTagMerge.reset(
            tankTags = "hand:written, rating:⭐⭐⭐, artist:old, source:web",
            membersTags = listOf("artist:a, date_added:1", "parody:p"),
        )
        assertEquals("rating:⭐⭐⭐, source:web, artist:a, parody:p", result)
    }

    // ---- auto-fill gate (§4.5) ----

    @Test
    fun needsAutoFill_onlyWhenNoNonExcludedTagsAndMembersExist() {
        assertEquals(true, TankTagMerge.needsAutoFill(tankTags = "rating:⭐⭐", memberCount = 3))
        assertEquals(true, TankTagMerge.needsAutoFill(tankTags = null, memberCount = 1))
        assertEquals(false, TankTagMerge.needsAutoFill(tankTags = "artist:a", memberCount = 3))
        assertEquals(false, TankTagMerge.needsAutoFill(tankTags = "", memberCount = 0))
    }
}
