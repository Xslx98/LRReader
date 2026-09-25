package com.lanraragi.reader.tankoubon

import org.junit.Assert.assertEquals
import org.junit.Test

class TankMemberOrderOpsTest {

    private val order = listOf("a", "b", "c", "d", "e", "f")

    @Test
    fun moveToTopKeepsBlockOrder() {
        assertEquals(listOf("b", "e", "a", "c", "d", "f"), TankMemberOrderOps.moveToTop(order, setOf("e", "b")))
    }

    @Test
    fun moveToBottomKeepsBlockOrder() {
        assertEquals(listOf("a", "c", "d", "f", "b", "e"), TankMemberOrderOps.moveToBottom(order, setOf("e", "b")))
    }

    @Test
    fun moveToLandsFirstSelectedAtPosition() {
        assertEquals(listOf("a", "c", "b", "e", "d", "f"), TankMemberOrderOps.moveTo(order, setOf("b", "e"), 3))
        assertEquals(listOf("b", "e", "a", "c", "d", "f"), TankMemberOrderOps.moveTo(order, setOf("b", "e"), 1))
        assertEquals(listOf("a", "c", "d", "f", "b", "e"), TankMemberOrderOps.moveTo(order, setOf("b", "e"), 6))
    }

    @Test
    fun moveToClampsOutOfRangePositions() {
        assertEquals(listOf("b", "a", "c", "d", "e", "f"), TankMemberOrderOps.moveTo(order, setOf("b"), -4))
        assertEquals(listOf("a", "c", "d", "e", "f", "b"), TankMemberOrderOps.moveTo(order, setOf("b"), 99))
    }

    @Test
    fun insertBeforeTarget() {
        assertEquals(listOf("a", "b", "c", "e", "f", "d"), TankMemberOrderOps.insertBefore(order, setOf("e", "f"), "d"))
        assertEquals(listOf("f", "a", "b", "c", "d", "e"), TankMemberOrderOps.insertBefore(order, setOf("f"), "a"))
    }

    @Test
    fun insertBeforeSelectedOrUnknownTargetIsNoOp() {
        assertEquals(order, TankMemberOrderOps.insertBefore(order, setOf("b", "c"), "c"))
        assertEquals(order, TankMemberOrderOps.insertBefore(order, setOf("b"), "zzz"))
    }

    @Test
    fun reverseAll() {
        assertEquals(order.reversed(), TankMemberOrderOps.reverse(order))
    }

    @Test
    fun reverseSelectedSwapsOnlyTheSelectedSlots() {
        assertEquals(listOf("a", "e", "c", "d", "b", "f"), TankMemberOrderOps.reverseSelected(order, setOf("b", "e")))
        assertEquals(listOf("a", "f", "c", "d", "e", "b"), TankMemberOrderOps.reverseSelected(order, setOf("b", "e", "f")))
    }

    @Test
    fun unknownSelectionsAreIgnored() {
        assertEquals(order, TankMemberOrderOps.moveToTop(order, setOf("zzz")))
        assertEquals(order, TankMemberOrderOps.moveTo(order, emptySet(), 3))
    }

    @Test
    fun sortByTitleIsDeterministicOnTies() {
        val titles = mapOf("a" to "same", "b" to "same", "c" to "same")
        assertEquals(listOf("a", "b", "c"), TankMemberOrderOps.sortByTitle(listOf("c", "a", "b")) { titles.getValue(it) })
    }
}
