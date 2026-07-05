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

    @Test
    fun toArchiveList_dropsTanks_byDefault() {
        val json = """{"data":[
            {"arcid":"TANK_1688616437","title":"Tank","tags":"","isnew":"false","extension":"","filename":"","pagecount":0,"progress":0,"lastreadtime":0},
            {"arcid":"${"a".repeat(40)}","title":"A","tags":"","isnew":"false","extension":"zip","filename":"a.zip","pagecount":10,"progress":0,"lastreadtime":0}
        ],"draw":1,"recordsFiltered":2,"recordsTotal":2}"""
        val result = lrrJson.decodeFromString<LRRSearchResult>(json)

        val out = result.toArchiveList()

        assertEquals(1, out.size)
        assertEquals("a".repeat(40), out[0].arcid)
    }

    @Test
    fun toArchiveList_mapsTanks_whenIncluded() {
        val json = """{"data":[
            {"arcid":"TANK_1688616437","title":"My Tank","tags":"artist:x","isnew":"false","extension":"","filename":"","pagecount":0,"progress":0,"lastreadtime":0}
        ],"draw":1,"recordsFiltered":1,"recordsTotal":1}"""
        val result = lrrJson.decodeFromString<LRRSearchResult>(json)

        val out = result.toArchiveList(includeTanks = true, tankProfileId = 3L, tankBaseUrl = "http://h:3000")

        assertEquals(1, out.size)
        assertEquals("TANK_1688616437", out[0].arcid)
        assertEquals("My Tank", out[0].title)
        assertEquals(3L, out[0].serverProfileId)
        assertTrue(out[0].thumbnailUrl.contains("/api/tankoubons/TANK_1688616437/thumbnail"))
    }

    @Test
    fun toArchiveList_includeTanks_nullBaseUrl_dropsTanksInsteadOfCrashing() {
        val json = """{"data":[
            {"arcid":"TANK_1688616437","title":"T","tags":"","isnew":"false","extension":"","filename":"","pagecount":0,"progress":0,"lastreadtime":0}
        ],"draw":1,"recordsFiltered":1,"recordsTotal":1}"""
        val result = lrrJson.decodeFromString<LRRSearchResult>(json)
        assertEquals(0, result.toArchiveList(includeTanks = true, tankProfileId = 3L, tankBaseUrl = null).size)
    }
}
