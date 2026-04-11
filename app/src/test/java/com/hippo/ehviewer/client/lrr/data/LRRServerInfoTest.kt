package com.lanraragi.reader.client.api.data

import com.lanraragi.reader.client.api.*
import com.lanraragi.reader.client.api.data.*
import org.junit.Assert.*
import org.junit.Test

class LRRServerInfoTest {

    @Test
    fun parseFullResponse() {
        val json = """{
            "name": "My LANraragi",
            "motd": "Welcome!",
            "version": "0.9.21",
            "version_name": "Chaotic Century",
            "has_password": true,
            "debug_mode": false,
            "nofun_mode": false,
            "archives_per_page": 100,
            "server_resizes_images": true,
            "server_tracks_progress": true,
            "cache_last_cleared": 1700000000
        }"""
        val info = lrrJson.decodeFromString<LRRServerInfo>(json)
        assertEquals("My LANraragi", info.name)
        assertEquals("Welcome!", info.motd)
        assertEquals("0.9.21", info.version)
        assertEquals("Chaotic Century", info.versionName)
        assertTrue(info.hasPassword)
        assertFalse(info.debugMode)
        assertEquals(100, info.archivesPerPage)
        assertTrue(info.serverResizesImages)
        assertTrue(info.serverTracksProgress)
        assertEquals(1700000000L, info.cacheLastCleared)
    }

    @Test
    fun parseWithMissingOptionalFields() {
        val json = """{"name": "Minimal", "version": "0.9.0"}"""
        val info = lrrJson.decodeFromString<LRRServerInfo>(json)
        assertEquals("Minimal", info.name)
        assertEquals("0.9.0", info.version)
        assertNull(info.motd)
        assertNull(info.versionName)
        assertFalse(info.hasPassword)
    }

    @Test
    fun toStringFormat() {
        val json = """{"name": "Test", "version": "1.0", "version_name": "Alpha", "has_password": true}"""
        val info = lrrJson.decodeFromString<LRRServerInfo>(json)
        val str = info.toString()
        assertTrue(str.contains("Test"))
        assertTrue(str.contains("1.0"))
        assertTrue(str.contains("Alpha"))
    }
}
