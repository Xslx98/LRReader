package com.lanraragi.reader.tankoubon

import org.junit.Assert.assertEquals
import org.junit.Test

class TankRecentStoreTest {

    private class MemoryStorage : TankRecentStore.Storage {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String?) { this.value = value }
    }

    @Test
    fun `touch moves a tank to the front and survives a reopen`() {
        val storage = MemoryStorage()
        val store = TankRecentStore(storage)

        store.touch(7L, "T1")
        store.touch(7L, "T2")
        store.touch(7L, "T1")

        assertEquals(listOf("T1", "T2"), TankRecentStore(storage).get(7L))
    }

    @Test
    fun `the list is capped at the limit`() {
        val store = TankRecentStore(MemoryStorage())

        for (i in 1..TankRecentStore.LIMIT + 2) store.touch(7L, "T$i")

        assertEquals(TankRecentStore.LIMIT, store.get(7L).size)
        assertEquals("T${TankRecentStore.LIMIT + 2}", store.get(7L).first())
    }

    @Test
    fun `profiles are kept apart and removeProfile drops only one`() {
        val store = TankRecentStore(MemoryStorage())
        store.touch(1L, "A")
        store.touch(2L, "B")

        store.removeProfile(1L)

        assertEquals(emptyList<String>(), store.get(1L))
        assertEquals(listOf("B"), store.get(2L))
    }

    @Test
    fun `remove forgets a deleted tank`() {
        val store = TankRecentStore(MemoryStorage())
        store.touch(1L, "A")
        store.touch(1L, "B")

        store.remove(1L, "A")

        assertEquals(listOf("B"), store.get(1L))
    }

    @Test
    fun `garbage in the slot reads as empty`() {
        val storage = MemoryStorage().apply { value = "not json" }

        assertEquals(emptyList<String>(), TankRecentStore(storage).get(1L))
    }
}
