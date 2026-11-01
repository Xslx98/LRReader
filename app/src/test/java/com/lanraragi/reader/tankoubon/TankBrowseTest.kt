package com.lanraragi.reader.tankoubon

import com.lanraragi.reader.client.api.LRRTankoubonApi.Tankoubon
import com.lanraragi.reader.tankoubon.TankBrowse.Sort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TankBrowseTest {

    private fun tank(id: String, name: String, members: Int = 0, tags: String? = null) =
        Tankoubon(id = id, name = name, archives = List(members) { "$id-$it" }, tags = tags)

    private val tanks = listOf(
        tank("T1", "Series 10", members = 2),
        tank("T2", "Series 2", members = 5),
        tank("T3", "alpha", members = 5),
        tank("T4", "Beta", members = 0),
    )

    private fun List<Tankoubon>.ids() = map { it.id }

    @Test
    fun `name sort is natural and case-insensitive`() {
        assertEquals(listOf("T3", "T4", "T2", "T1"), TankBrowse.order(tanks, "", Sort.NAME, emptyMap()).ids())
    }

    @Test
    fun `member sort puts the largest first and breaks ties by name`() {
        assertEquals(listOf("T3", "T2", "T1", "T4"), TankBrowse.order(tanks, "", Sort.MEMBERS, emptyMap()).ids())
    }

    @Test
    fun `recently read sort leads with the newest read and leaves unread tanks last by name`() {
        val lastRead = mapOf("T1" to 100L, "T4" to 300L)

        assertEquals(listOf("T4", "T1", "T3", "T2"), TankBrowse.order(tanks, "", Sort.RECENT_READ, lastRead).ids())
    }

    @Test
    fun `a query filters and ranks like the picker regardless of sort`() {
        val withTags = tanks + tank("T5", "Other", tags = "series:x")

        assertEquals(
            listOf("T2", "T1", "T5"),
            TankBrowse.order(withTags, " series", Sort.MEMBERS, emptyMap()).ids(),
        )
    }

    @Test
    fun `toArchive carries the tank as a TANK_ row with parsed tags and an optional cover`() {
        val t = Tankoubon(id = "TANK_1234567890", name = "Vol", archives = listOf("a", "b"), tags = "artist:x, series:y", progress = 7)

        val plain = TankBrowse.toArchive(t, profileId = 3L, baseUrl = "http://h", coverOverride = null, lastRead = 42L)
        assertEquals("TANK_1234567890", plain.arcid)
        assertEquals("Vol", plain.title)
        assertEquals(listOf("x"), plain.tags["artist"])
        assertEquals(7, plain.progress)
        assertEquals(42L, plain.lastreadtime)
        assertEquals(3L, plain.serverProfileId)
        assertTrue(plain.thumbnailUrl.contains("TANK_1234567890"))

        val covered = TankBrowse.toArchive(t, 3L, "http://h", coverOverride = "http://h/cover", lastRead = 0L)
        assertEquals("http://h/cover", covered.thumbnailUrl)
    }
}
