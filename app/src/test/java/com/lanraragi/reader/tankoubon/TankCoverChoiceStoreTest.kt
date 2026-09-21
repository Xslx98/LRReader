package com.lanraragi.reader.tankoubon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TankCoverChoiceStoreTest {

    private class MemoryStorage : TankCoverChoiceStore.Storage {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String?) { this.value = value }
    }

    private val a = "a".repeat(40)
    private val b = "b".repeat(40)

    @Test
    fun `put then get round-trips through the string slot`() {
        val storage = MemoryStorage()
        val store = TankCoverChoiceStore(storage)

        store.put("TANK_1", TankCoverChoiceStore.Choice(a, page0 = 3, profileId = 7L))
        store.put("TANK_2", TankCoverChoiceStore.Choice(b, page0 = 0, profileId = 7L))

        val reopened = TankCoverChoiceStore(storage)
        assertEquals(TankCoverChoiceStore.Choice(a, 3, 7L), reopened.get("TANK_1"))
        assertEquals(TankCoverChoiceStore.Choice(b, 0, 7L), reopened.get("TANK_2"))
        assertNull(reopened.get("TANK_3"))
    }

    @Test
    fun `reconcile keeps the choice while its archive is a member`() {
        val store = TankCoverChoiceStore(MemoryStorage())
        store.put("TANK_1", TankCoverChoiceStore.Choice(a, 2, 7L))

        assertEquals(TankCoverChoiceStore.Choice(a, 2, 7L), store.reconcile("TANK_1", listOf(b, a)))
        assertEquals(TankCoverChoiceStore.Choice(a, 2, 7L), store.get("TANK_1"))
    }

    @Test
    fun `reconcile drops the choice once its archive left the tank`() {
        val storage = MemoryStorage()
        val store = TankCoverChoiceStore(storage)
        store.put("TANK_1", TankCoverChoiceStore.Choice(a, 2, 7L))

        assertNull(store.reconcile("TANK_1", listOf(b)))
        assertNull(store.get("TANK_1"))
        assertNull("last entry removed clears the slot", storage.value)
    }

    @Test
    fun `remove and garbage in the slot are tolerated`() {
        val storage = MemoryStorage().apply { value = "not json" }
        val store = TankCoverChoiceStore(storage)
        assertNull(store.get("TANK_1"))
        store.remove("TANK_1")
        store.put("TANK_1", TankCoverChoiceStore.Choice(a, 1, 1L))
        store.remove("TANK_1")
        assertNull(store.get("TANK_1"))
    }
}
