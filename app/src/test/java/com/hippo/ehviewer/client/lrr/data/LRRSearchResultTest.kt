package com.lanraragi.reader.client.api.data

import com.lanraragi.reader.client.api.*
import com.lanraragi.reader.client.api.data.*
import org.junit.Assert.*
import org.junit.Test

class LRRSearchResultTest {

    @Test
    fun parseSearchResponse() {
        val json = """{
            "data": [
                {"arcid":"a1","title":"Archive 1","tags":"tag1","isnew":"false","extension":"zip","filename":"a1.zip","pagecount":10,"progress":0,"lastreadtime":0},
                {"arcid":"a2","title":"Archive 2","tags":"tag2","isnew":"true","extension":"cbz","filename":"a2.cbz","pagecount":20,"progress":5,"lastreadtime":100}
            ],
            "draw": 1,
            "recordsFiltered": 2,
            "recordsTotal": 100
        }"""
        val result = lrrJson.decodeFromString<LRRSearchResult>(json)
        assertEquals(2, result.data.size)
        assertEquals("a1", result.data[0].arcid)
        assertEquals("Archive 2", result.data[1].title)
        assertEquals(1, result.draw)
        assertEquals(2, result.recordsFiltered)
        assertEquals(100, result.recordsTotal)
    }

    @Test
    fun parseEmptyResult() {
        val json = """{"data": [], "draw": 0, "recordsFiltered": 0, "recordsTotal": 0}"""
        val result = lrrJson.decodeFromString<LRRSearchResult>(json)
        assertTrue(result.data.isEmpty())
        assertEquals(0, result.recordsFiltered)
        assertEquals(0, result.recordsTotal)
    }
}
