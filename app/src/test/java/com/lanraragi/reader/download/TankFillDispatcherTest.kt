package com.lanraragi.reader.download

import com.lanraragi.reader.domain.Archive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure "fill the tank" split shared by the tank detail overflow, the
 * downloads card start control and the drawer long-press (spec 2026-09-21
 * §4): FINISH members are skipped, partial/failed members restart, missing
 * members enqueue.
 */
class TankFillDispatcherTest {

    private fun archive(arcid: String) = Archive(
        arcid, "T $arcid", emptyMap(), 3, 0, "zip", "$arcid.zip", "", 0f, false, 0L, null, 1L,
    )

    @Test
    fun `members split by download state`() {
        val states = mapOf(
            "new" to DownloadState.INVALID,
            "failed" to DownloadState.FAILED,
            "partial" to DownloadState.NONE,
            "done" to DownloadState.FINISH,
            "busy" to DownloadState.DOWNLOAD,
        )
        val plan = TankFillDispatcher.plan(states.keys.map(::archive)) { states.getValue(it) }

        assertEquals(listOf("new"), plan.toAdd.map { it.arcid })
        assertEquals(listOf("failed", "partial", "busy"), plan.toRestart)
        assertEquals(1, plan.alreadyLocal)
        assertEquals(4, plan.queued)
    }

    @Test
    fun `all local plan queues nothing`() {
        val plan = TankFillDispatcher.plan(listOf(archive("a"), archive("b"))) { DownloadState.FINISH }
        assertTrue(plan.toAdd.isEmpty())
        assertTrue(plan.toRestart.isEmpty())
        assertEquals(2, plan.alreadyLocal)
        assertEquals(0, plan.queued)
    }

    @Test
    fun `empty members give an empty plan`() {
        val plan = TankFillDispatcher.plan(emptyList()) { DownloadState.INVALID }
        assertEquals(0, plan.queued)
        assertEquals(0, plan.alreadyLocal)
    }
}

/** Member resolution for an INCOMPLETE card: present rows + fetched missing ones, tank order kept. */
class TankFillDispatcherResolveTest {

    private fun archive(arcid: String) = Archive(
        arcid, "T $arcid", emptyMap(), 3, 0, "zip", "$arcid.zip", "", 0f, false, 0L, null, 1L,
    )

    @Test
    fun `missing members are fetched and merged in tank order`() = kotlinx.coroutines.test.runTest {
        val fetched = ArrayList<String>()
        val resolved = TankFillDispatcher.resolveMembers(
            memberIdsInOrder = listOf("a", "b", "c"),
            present = listOf(archive("c"), archive("a")),
        ) { id -> fetched.add(id); archive(id) }

        assertEquals(listOf("b"), fetched)
        assertEquals(listOf("a", "b", "c"), resolved.members.map { it.arcid })
        assertTrue(resolved.unresolved.isEmpty())
    }

    @Test
    fun `a failed fetch is reported and the rest still resolve`() = kotlinx.coroutines.test.runTest {
        val resolved = TankFillDispatcher.resolveMembers(
            memberIdsInOrder = listOf("a", "b"),
            present = emptyList(),
        ) { id -> if (id == "b") null else archive(id) }

        assertEquals(listOf("a"), resolved.members.map { it.arcid })
        assertEquals(listOf("b"), resolved.unresolved)
    }
}
