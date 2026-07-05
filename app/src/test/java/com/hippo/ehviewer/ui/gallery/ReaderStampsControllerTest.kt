package com.hippo.ehviewer.ui.gallery

import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.client.api.LRRStampApi.StampData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderStampsControllerTest {

    private class FakeBackend : StampsBackend {
        var pages: List<Int> = emptyList()
        var stamps: MutableMap<Int, MutableList<StampData>> = mutableMapOf()
        var failWith: Throwable? = null
        var indexCalls = 0
        var pageCalls = 0

        /** When set, stampedPages() parks on this until the test completes it. */
        var indexGate: CompletableDeferred<List<Int>>? = null

        override suspend fun stampedPages(): List<Int> {
            indexCalls++
            failWith?.let { throw it }
            indexGate?.let { return it.await() }
            return pages
        }
        override suspend fun stampsByPage(page1: Int): List<StampData> {
            pageCalls++
            failWith?.let { throw it }
            return stamps[page1].orEmpty()
        }
        override suspend fun add(page1: Int, content: String, position: String): String {
            failWith?.let { throw it }
            val id = "STAMPS_${page1}_${1000 + pageCalls}"
            stamps.getOrPut(page1) { mutableListOf() }.add(StampData(id, position, content))
            return id
        }
        override suspend fun update(stampId: String, content: String?, position: String?) {
            failWith?.let { throw it }
        }
        override suspend fun delete(stampId: String) {
            failWith?.let { throw it }
        }
    }

    @Test
    fun refreshIndex_success_cachesSortedPages() = runTest {
        val backend = FakeBackend().apply { pages = listOf(7, 2) }
        val c = ReaderStampsController(this, backend) {}
        c.refreshIndex()
        advanceUntilIdle()
        assertEquals(ReaderStampsController.Support.SUPPORTED, c.support)
        assertEquals(listOf(2, 7), c.stampedPagesSorted())
    }

    @Test
    fun refreshIndex_404_disablesAndStopsCalling() = runTest {
        val backend = FakeBackend().apply { failWith = LRRHttpException(404) }
        val c = ReaderStampsController(this, backend) {}
        c.refreshIndex()
        advanceUntilIdle()
        assertEquals(ReaderStampsController.Support.UNSUPPORTED, c.support)
        c.refreshIndex()
        advanceUntilIdle()
        assertEquals(1, backend.indexCalls)
    }

    @Test
    fun refreshIndex_ioError_staysRetryable() = runTest {
        val backend = FakeBackend().apply { failWith = IOException("offline") }
        val c = ReaderStampsController(this, backend) {}
        c.refreshIndex()
        advanceUntilIdle()
        assertEquals(ReaderStampsController.Support.UNKNOWN, c.support)
        backend.failWith = null
        backend.pages = listOf(1)
        c.refreshIndex()
        advanceUntilIdle()
        assertEquals(ReaderStampsController.Support.SUPPORTED, c.support)
    }

    @Test
    fun ensureVisiblePagesLoaded_loadsOnlyStampedPages() = runTest {
        val backend = FakeBackend().apply {
            pages = listOf(3)
            stamps[3] = mutableListOf(StampData("STAMPS_3_1", "10,10", "hi"))
        }
        val c = ReaderStampsController(this, backend) {}
        c.refreshIndex()
        advanceUntilIdle()
        c.ensureVisiblePagesLoaded(listOf(0, 1, 2)) // pages0 -> pages1 = 1,2,3
        advanceUntilIdle()
        assertEquals(1, backend.pageCalls)
        assertEquals("hi", c.stampsForDisplayPage(2).single().content)
        assertTrue(c.stampsForDisplayPage(0).isEmpty())
    }

    @Test
    fun addStamp_appendsCacheAndIndex() = runTest {
        val backend = FakeBackend().apply { pages = emptyList() }
        val c = ReaderStampsController(this, backend) {}
        c.refreshIndex()
        advanceUntilIdle()
        var error: Throwable? = RuntimeException("sentinel")
        c.addStamp(page0 = 4, content = "note", normX = 10f, normY = 20f) { error = it }
        advanceUntilIdle()
        assertNull(error)
        assertEquals(listOf(5), c.stampedPagesSorted())
        assertEquals("note", c.stampsForDisplayPage(4).single().content)
        assertEquals("10.00,20.00", c.stampsForDisplayPage(4).single().position)
    }

    @Test
    fun updateStamp_rewritesCachedEntry() = runTest {
        val backend = FakeBackend().apply {
            pages = listOf(1)
            stamps[1] = mutableListOf(StampData("STAMPS_1_1", "10,10", "old"))
        }
        val c = ReaderStampsController(this, backend) {}
        c.refreshIndex(); advanceUntilIdle()
        c.ensureVisiblePagesLoaded(listOf(0)); advanceUntilIdle()
        c.updateStamp(page1 = 1, stampId = "STAMPS_1_1", content = "new") {}
        advanceUntilIdle()
        assertEquals("new", c.stampsForDisplayPage(0).single().content)
        assertEquals("10,10", c.stampsForDisplayPage(0).single().position)
    }

    @Test
    fun deleteStamp_prunesEmptyPageFromIndex() = runTest {
        val backend = FakeBackend().apply {
            pages = listOf(1)
            stamps[1] = mutableListOf(StampData("STAMPS_1_1", "10,10", "x"))
        }
        val c = ReaderStampsController(this, backend) {}
        c.refreshIndex(); advanceUntilIdle()
        c.ensureVisiblePagesLoaded(listOf(0)); advanceUntilIdle()
        c.deleteStamp(page1 = 1, stampId = "STAMPS_1_1") {}
        advanceUntilIdle()
        assertTrue(c.stampsForDisplayPage(0).isEmpty())
        assertEquals(emptyList<Int>(), c.stampedPagesSorted())
    }

    @Test
    fun refreshIndex_staleSnapshot_keepsInterleavedAdd() = runTest {
        val gate = CompletableDeferred<List<Int>>()
        val backend = FakeBackend().apply { indexGate = gate }
        val c = ReaderStampsController(this, backend) {}
        c.refreshIndex()
        runCurrent() // index fetch parks on the gate
        var error: Throwable? = RuntimeException("sentinel")
        c.addStamp(page0 = 4, content = "note", normX = 10f, normY = 20f) { error = it }
        runCurrent()
        assertNull(error)
        // Slow index snapshot resolves AFTER the add; it must not erase page 5.
        gate.complete(listOf(1))
        advanceUntilIdle()
        assertEquals(listOf(1, 5), c.stampedPagesSorted())
    }

    @Test
    fun refreshIndex_staleSnapshot_respectsInterleavedDelete() = runTest {
        val backend = FakeBackend().apply {
            pages = listOf(1)
            stamps[1] = mutableListOf(StampData("STAMPS_1_1", "10,10", "x"))
        }
        val c = ReaderStampsController(this, backend) {}
        c.refreshIndex(); advanceUntilIdle()
        c.ensureVisiblePagesLoaded(listOf(0)); advanceUntilIdle()
        c.deleteStamp(page1 = 1, stampId = "STAMPS_1_1") {}
        advanceUntilIdle()
        // Second refresh returns the stale snapshot [1] (fake's `pages` is
        // unchanged); local truth — page 1 known empty — must win.
        c.refreshIndex()
        advanceUntilIdle()
        assertEquals(emptyList<Int>(), c.stampedPagesSorted())
    }

    @Test
    fun refreshIndex_secondCallWhileLoading_stillGetsCallbackOnResolve() = runTest {
        val gate = CompletableDeferred<List<Int>>()
        val backend = FakeBackend().apply { indexGate = gate }
        val c = ReaderStampsController(this, backend) {}
        var firstResult: Boolean? = null
        var secondResult: Boolean? = null
        c.refreshIndex { firstResult = it }
        runCurrent() // first fetch parks on the gate; loadingIndex is now true
        c.refreshIndex { secondResult = it }
        runCurrent()
        // Second call must not have started a second backend fetch, and must
        // not have dropped its callback just because a load was in flight.
        assertEquals(1, backend.indexCalls)
        assertNull(firstResult)
        assertNull(secondResult)
        gate.complete(listOf(2))
        advanceUntilIdle()
        assertEquals(true, firstResult)
        assertEquals(true, secondResult)
        assertEquals(listOf(2), c.stampedPagesSorted())
    }

    @Test
    fun writeFailure_propagatesAndKeepsCache() = runTest {
        val backend = FakeBackend().apply {
            pages = listOf(1)
            stamps[1] = mutableListOf(StampData("STAMPS_1_1", "10,10", "x"))
        }
        val c = ReaderStampsController(this, backend) {}
        c.refreshIndex(); advanceUntilIdle()
        c.ensureVisiblePagesLoaded(listOf(0)); advanceUntilIdle()
        backend.failWith = LRRHttpException(423, "Stamp locked")
        var error: Throwable? = null
        c.deleteStamp(page1 = 1, stampId = "STAMPS_1_1") { error = it }
        advanceUntilIdle()
        assertNotNull(error)
        assertEquals("x", c.stampsForDisplayPage(0).single().content)
    }
}
