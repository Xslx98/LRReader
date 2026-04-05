package com.hippo.ehviewer.client.lrr.data

import com.hippo.ehviewer.client.lrr.lrrJson
import org.junit.Assert.*
import org.junit.Test

class LRRTagStatTest {

    @Test
    fun parseBasicTagStat() {
        val json = """{"namespace":"artist","text":"some_artist","weight":15}"""
        val tag = lrrJson.decodeFromString<LRRTagStat>(json)
        assertEquals("artist", tag.namespace)
        assertEquals("some_artist", tag.text)
        assertEquals(15, tag.weight)
    }

    @Test
    fun parseWithDefaults() {
        val json = """{}"""
        val tag = lrrJson.decodeFromString<LRRTagStat>(json)
        assertEquals("", tag.namespace)
        assertEquals("", tag.text)
        assertEquals(0, tag.weight)
    }

    @Test
    fun parseArray() {
        val json = """[
            {"namespace":"artist","text":"foo","weight":5},
            {"namespace":"parody","text":"bar","weight":10},
            {"namespace":"date_added","text":"1700000","weight":1}
        ]"""
        val tags = lrrJson.decodeFromString<List<LRRTagStat>>(json)
        assertEquals(3, tags.size)
        assertEquals("artist", tags[0].namespace)
        assertEquals("parody", tags[1].namespace)
        assertEquals("date_added", tags[2].namespace)
    }

    @Test
    fun parseUnknownFieldsIgnored() {
        val json = """{"namespace":"artist","text":"test","weight":3,"extra_field":"ignored"}"""
        val tag = lrrJson.decodeFromString<LRRTagStat>(json)
        assertEquals("artist", tag.namespace)
        assertEquals("test", tag.text)
        assertEquals(3, tag.weight)
    }

    @Test
    fun dataClassEquality() {
        val a = LRRTagStat("artist", "foo", 5)
        val b = LRRTagStat("artist", "foo", 5)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun dataClassCopy() {
        val original = LRRTagStat("artist", "foo", 5)
        val modified = original.copy(weight = 10)
        assertEquals(10, modified.weight)
        assertEquals("artist", modified.namespace)
        assertEquals("foo", modified.text)
    }
}
