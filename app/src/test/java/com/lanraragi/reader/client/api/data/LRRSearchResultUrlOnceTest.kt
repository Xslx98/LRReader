package com.lanraragi.reader.client.api.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.lrrJson
import com.lanraragi.reader.ui.scene.gallery.list.GallerySearchHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit 06e PERF-01: mapping a search page reads the server URL (an
 * encrypted-store value) once per page, not once per archive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class LRRSearchResultUrlOnceTest {

    /** Delegating prefs that counts server-URL reads. */
    private class CountingPrefs(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        var urlReads = 0

        override fun getString(key: String?, defValue: String?): String? {
            if (key == "server_url") urlReads++
            return delegate.getString(key, defValue)
        }
    }

    private lateinit var counting: CountingPrefs

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val real = context.getSharedPreferences("lrr_search_url_once_test", Context.MODE_PRIVATE)
        real.edit().clear().commit()
        counting = CountingPrefs(real)
        LRRAuthManager.initializeForTesting(counting)
        LRRAuthManager.setServerUrl("http://h:3000")
        counting.urlReads = 0
    }

    @After
    fun tearDown() {
        LRRAuthManager.clear()
    }

    private fun page(size: Int): LRRSearchResult {
        val entries = (1..size).joinToString(",") { i ->
            """{"arcid":"${"%040x".format(i)}","title":"A$i","tags":"","isnew":"false",""" +
                """"extension":"zip","filename":"a$i.zip","pagecount":10,"progress":0,"lastreadtime":0}"""
        }
        return lrrJson.decodeFromString<LRRSearchResult>(
            """{"data":[$entries],"draw":1,"recordsFiltered":$size,"recordsTotal":$size}"""
        )
    }

    @Test
    fun toArchiveList_reads_the_server_url_once_per_page() {
        val out = page(50).toArchiveList()

        assertEquals(50, out.size)
        assertTrue(out.all { it.thumbnailUrl.startsWith("http://h:3000/") })
        assertEquals("a 50-entry page must read the server URL once", 1, counting.urlReads)
    }

    @Test
    fun convertLRRSearchResult_reads_the_server_url_once_per_page() {
        val out = GallerySearchHelper.convertLRRSearchResult(page(50), 0)

        assertEquals(50, out.galleryInfoList.size)
        assertTrue(out.galleryInfoList.all { it.thumbnailUrl.startsWith("http://h:3000/") })
        assertEquals(1, counting.urlReads)
    }
}
