package com.lanraragi.reader.tankoubon

import com.lanraragi.reader.client.api.data.LRRCategory
import com.lanraragi.reader.tankoubon.TankCategoryPromotion.Changes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TankCategoryPromotion] rules (spec 2026-09-22 §6) over static categories. */
class TankCategoryPromotionTest {

    private fun cat(id: String, vararg members: String, search: String? = "") = LRRCategory().apply {
        this.id = id
        this.name = id
        this.archives = members.toList()
        this.search = search
    }

    private val tank = "TANK_1700000000"
    private val a = "a".repeat(40)
    private val b = "b".repeat(40)
    private val m = "m".repeat(40)

    @Test
    fun onAdd_joinsEveryStaticCategoryOfANewMemberTheTankIsNotIn() {
        val cats = listOf(
            cat("SET_1", a),            // new member → join
            cat("SET_2", a, tank),      // already in → nothing
            cat("SET_3", b),            // unrelated member → nothing
            cat("SET_4", a, search = "artist:x"), // dynamic → ignored
        )
        assertEquals(Changes(listOf("SET_1"), emptyList()), TankCategoryPromotion.onAdd(cats, tank, listOf(a)))
    }

    @Test
    fun onAdd_withNoCategoriesIsEmpty() {
        assertTrue(TankCategoryPromotion.onAdd(emptyList(), tank, listOf(a)).isEmpty)
    }

    @Test
    fun onRemove_rule3_manualCategoriesStayOthersLeaveWhenNoMemberRemains() {
        val cats = listOf(
            cat("SET_manual", tank),        // no member ever explained it → stays
            cat("SET_removed", a, tank),    // only the removed member → leave
            cat("SET_shared", a, m, tank),  // remaining member m → stays
            cat("SET_other", b),            // tank not in it → nothing
        )
        assertEquals(
            Changes(emptyList(), listOf("SET_removed")),
            TankCategoryPromotion.onRemove(cats, tank, memberIdsBefore = listOf(a, m), removedIds = listOf(a)),
        )
    }

    @Test
    fun onRemove_lastMemberLeavesOnlyManualCategories() {
        val cats = listOf(cat("SET_manual", tank), cat("SET_a", a, tank))
        assertEquals(
            Changes(emptyList(), listOf("SET_a")),
            TankCategoryPromotion.onRemove(cats, tank, listOf(a), listOf(a)),
        )
    }

    @Test
    fun onDissolve_removesTheTankFromEveryStaticCategory() {
        val cats = listOf(cat("SET_1", tank, a), cat("SET_2", b), cat("SET_3", tank), cat("SET_dyn", tank, search = "x"))
        assertEquals(Changes(emptyList(), listOf("SET_1", "SET_3")), TankCategoryPromotion.onDissolve(cats, tank))
    }

    @Test
    fun reset_makesTankCategoriesExactlyTheMembersUnion() {
        val cats = listOf(
            cat("SET_add", a),           // member, tank missing → add
            cat("SET_keep", m, tank),    // member and tank → keep
            cat("SET_drop", tank),       // manual, no member → remove
            cat("SET_none", b),          // neither → nothing
        )
        assertEquals(
            Changes(listOf("SET_add"), listOf("SET_drop")),
            TankCategoryPromotion.reset(cats, tank, listOf(a, m)),
        )
    }

    @Test
    fun categoriesWithoutIdAreIgnored() {
        val nameless = LRRCategory().apply { archives = listOf(a); search = "" }
        assertTrue(TankCategoryPromotion.onAdd(listOf(nameless), tank, listOf(a)).isEmpty)
    }
}
