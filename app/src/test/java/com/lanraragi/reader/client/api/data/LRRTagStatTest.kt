package com.lanraragi.reader.client.api.data

import com.lanraragi.reader.client.api.*
import com.lanraragi.reader.client.api.data.*
import org.junit.Assert.*
import org.junit.Test

class LRRTagStatTest {

    @Test
    fun parseWithDefaults() {
        val json = """{}"""
        val tag = lrrJson.decodeFromString<LRRTagStat>(json)
        assertNull(tag.namespace)
        assertEquals("", tag.text)
        assertEquals(0, tag.weight)
    }

    @Test
    fun parseUnknownFieldsIgnored() {
        val json = """{"namespace":"artist","text":"test","weight":3,"extra_field":"ignored"}"""
        val tag = lrrJson.decodeFromString<LRRTagStat>(json)
        assertEquals("artist", tag.namespace)
        assertEquals("test", tag.text)
        assertEquals(3, tag.weight)
    }
}
