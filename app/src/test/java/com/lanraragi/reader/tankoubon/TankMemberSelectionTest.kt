package com.lanraragi.reader.tankoubon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TankMemberSelectionTest {

    @Test
    fun longPressEntersAndChecks() {
        var changes = 0
        val s = TankMemberSelection { changes++ }
        assertFalse(s.isActive)
        s.enterAndToggle("a")
        assertTrue(s.isActive)
        assertTrue(s.isSelected("a"))
        assertEquals(1, changes)
    }

    @Test
    fun toggleIsIgnoredWhileInactive() {
        val s = TankMemberSelection()
        assertFalse(s.toggle("a"))
        assertFalse(s.isSelected("a"))
    }

    @Test
    fun uncheckingTheLastRowLeavesTheMode() {
        val s = TankMemberSelection()
        s.enterAndToggle("a")
        s.toggle("b")
        assertEquals(setOf("a", "b"), s.selected)
        s.toggle("a")
        assertTrue(s.isActive)
        s.toggle("b")
        assertFalse(s.isActive)
        assertEquals(0, s.count)
    }

    @Test
    fun selectAllKeepsListOrder() {
        val s = TankMemberSelection()
        s.enterAndToggle("c")
        s.selectAll(listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), s.selected.toList())
    }

    @Test
    fun retainAllDropsVanishedMembersAndLeavesWhenEmpty() {
        val s = TankMemberSelection()
        s.enterAndToggle("a")
        s.toggle("b")
        s.retainAll(listOf("b", "z"))
        assertEquals(setOf("b"), s.selected)
        s.retainAll(listOf("z"))
        assertFalse(s.isActive)
    }

    @Test
    fun clearResetsEverything() {
        val s = TankMemberSelection()
        s.enterAndToggle("a")
        s.clear()
        assertFalse(s.isActive)
        assertEquals(0, s.count)
    }
}
