package com.lanraragi.reader.download

import com.lanraragi.reader.dao.TankDownloadGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** arcid → downloaded-tank lookup the download service uses to treat a tank as one unit. */
class TankGroupIndexTest {

    private fun group(id: String, name: String, ids: String, created: Long = 0L) =
        TankDownloadGroup(id, 1L, name, ids, created)

    @Test
    fun `members map to their group, first group wins on overlap`() {
        val index = TankGroupIndex.build(
            listOf(group("TANK_1", "One", """["a","b"]"""), group("TANK_2", "Two", """["b","c"]""")),
        )
        assertEquals("TANK_1", index.getValue("a").tankId)
        assertEquals("TANK_1", index.getValue("b").tankId)
        assertEquals("Two", index.getValue("c").name)
        assertEquals(listOf("b", "c"), index.getValue("c").memberIds)
        assertNull(index["zzz"])
    }

    @Test
    fun `empty or corrupt groups yield an empty index`() {
        assertEquals(emptyMap<String, TankGroupIndex.Ref>(), TankGroupIndex.build(emptyList()))
        assertEquals(emptyMap<String, TankGroupIndex.Ref>(), TankGroupIndex.build(listOf(group("T", "x", "nope"))))
    }
}
