package com.lanraragi.reader.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Audit 2026-10-04 C06 (R1): newest N reports per kind, app-private, never more. */
class CrashLogStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_700_000_000_000L
    private fun store(keep: Int = 2) = CrashLogStore(tmp.root.resolve("crash"), keep) { now }

    @Test
    fun write_createsTheDirectoryAndTheFile() {
        val file = store().write(CrashLogStore.Kind.CRASH, "boom")
        assertTrue(file.name, file.name.startsWith("crash-"))
        assertEquals("boom", file.readText())
    }

    @Test
    fun write_prunesOlderReportsOfTheSameKindOnly() {
        val store = store(keep = 2)
        store.write(CrashLogStore.Kind.EXIT, "exit")
        repeat(4) {
            now += 1000
            store.write(CrashLogStore.Kind.CRASH, "crash $it")
        }
        val names = store.list().map { it.readText() }
        assertEquals(listOf("crash 3", "crash 2", "exit"), names)
    }

    @Test
    fun write_sameMillisecond_doesNotOverwrite() {
        val store = store(keep = 5)
        store.write(CrashLogStore.Kind.NON_FATAL, "a")
        store.write(CrashLogStore.Kind.NON_FATAL, "b")
        assertEquals(setOf("a", "b"), store.list().map { it.readText() }.toSet())
        // The collision-suffixed file is the newer one.
        assertEquals("b", store.list().first().readText())
    }

    @Test
    fun list_ignoresForeignFiles_andMissingDir() {
        assertEquals(emptyList<java.io.File>(), store().list())
        val dir = tmp.root.resolve("crash").apply { mkdirs() }
        dir.resolve("notes.txt").writeText("x")
        store().write(CrashLogStore.Kind.CRASH, "c")
        assertEquals(1, store().list().size)
    }
}
