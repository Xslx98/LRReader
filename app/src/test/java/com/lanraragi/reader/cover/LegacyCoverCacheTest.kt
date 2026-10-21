package com.lanraragi.reader.cover

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyCoverCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun purge_deletesLegacyDirs_andKeepsTheCoilCache() {
        val cache = tmp.newFolder()
        File(cache, "thumb/journal").apply { parentFile!!.mkdirs(); writeText("x") }
        File(cache, "thumb/abc.0").writeText("cover")
        File(cache, "spider_info/journal").apply { parentFile!!.mkdirs(); writeText("x") }
        val cover = File(cache, "cover/journal").apply { parentFile!!.mkdirs(); writeText("keep") }

        assertTrue(LegacyCoverCache.purge(cache))

        assertFalse(File(cache, "thumb").exists())
        assertFalse(File(cache, "spider_info").exists())
        assertTrue(cover.exists())
    }

    @Test
    fun purge_isANoop_whenNothingIsLeft() {
        assertTrue(LegacyCoverCache.purge(tmp.newFolder()))
    }
}
