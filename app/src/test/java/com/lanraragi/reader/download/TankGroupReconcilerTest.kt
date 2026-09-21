package com.lanraragi.reader.download

import com.lanraragi.reader.dao.TankDownloadGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure membership diff for a downloaded tank group against the server's
 * current member list (spec 2026-09-21 §1): ids, order and name follow the
 * server; other-profile groups are never touched; unchanged → null.
 */
class TankGroupReconcilerTest {

    private fun group(
        ids: String = """["m1","m2"]""",
        name: String = "Tank",
        profile: Long = 1L,
        created: Long = 123L,
    ) = TankDownloadGroup(TANK, profile, name, ids, created)

    @Test
    fun `unchanged membership and name yields null`() {
        val result = TankGroupReconciler.reconcile(
            group(), serverName = "Tank", serverMemberIds = listOf("m1", "m2"), activeProfileId = 1L,
        )
        assertNull(result)
    }

    @Test
    fun `added member is appended in server order and reported`() {
        val result = TankGroupReconciler.reconcile(
            group(), "Tank", listOf("m1", "m3", "m2"), 1L,
        )!!
        assertEquals("""["m1","m3","m2"]""", result.group.memberIdsJson)
        assertEquals(listOf("m3"), result.added)
        assertEquals(emptyList<String>(), result.removed)
    }

    @Test
    fun `removed member is dropped and reported`() {
        val result = TankGroupReconciler.reconcile(
            group(), "Tank", listOf("m2"), 1L,
        )!!
        assertEquals("""["m2"]""", result.group.memberIdsJson)
        assertEquals(emptyList<String>(), result.added)
        assertEquals(listOf("m1"), result.removed)
    }

    @Test
    fun `reorder alone rewrites the row without add or remove`() {
        val result = TankGroupReconciler.reconcile(
            group(), "Tank", listOf("m2", "m1"), 1L,
        )!!
        assertEquals("""["m2","m1"]""", result.group.memberIdsJson)
        assertEquals(emptyList<String>(), result.added)
        assertEquals(emptyList<String>(), result.removed)
    }

    @Test
    fun `rename alone rewrites the row`() {
        val result = TankGroupReconciler.reconcile(
            group(), "Renamed", listOf("m1", "m2"), 1L,
        )!!
        assertEquals("Renamed", result.group.name)
        assertEquals("""["m1","m2"]""", result.group.memberIdsJson)
    }

    @Test
    fun `identity fields survive the rewrite`() {
        val result = TankGroupReconciler.reconcile(
            group(created = 999L), "Tank", listOf("m1"), 1L,
        )!!
        assertEquals(TANK, result.group.tankId)
        assertEquals(1L, result.group.serverProfileId)
        assertEquals(999L, result.group.createdTime)
    }

    @Test
    fun `other-profile group is never reconciled`() {
        val result = TankGroupReconciler.reconcile(
            group(profile = 2L), "Other", listOf("zzz"), activeProfileId = 1L,
        )
        assertNull(result)
    }

    @Test
    fun `duplicate server ids collapse to first occurrence`() {
        val result = TankGroupReconciler.reconcile(
            group(), "Tank", listOf("m1", "m2", "m1"), 1L,
        )
        assertNull(result)
    }

    @Test
    fun `empty server list empties the row and reports every member removed`() {
        val result = TankGroupReconciler.reconcile(
            group(), "Tank", emptyList(), 1L,
        )!!
        assertEquals("[]", result.group.memberIdsJson)
        assertEquals(listOf("m1", "m2"), result.removed)
    }

    @Test
    fun `corrupt stored json is treated as an empty member list`() {
        val stored = group(ids = "not json")
        val result = TankGroupReconciler.reconcile(stored, "Tank", listOf("m1"), 1L)!!
        assertEquals("""["m1"]""", result.group.memberIdsJson)
        assertEquals(listOf("m1"), result.added)
        assertSame(stored.tankId, result.group.tankId)
    }

    private companion object {
        const val TANK = "TANK_1688000000"
    }
}
