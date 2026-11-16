package com.lanraragi.reader.download

import com.lanraragi.reader.dao.DownloadInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Unit tests for [DownloadEventBus] — listener management and main-thread dispatch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DownloadEventBusTest {

    private lateinit var eventBus: DownloadEventBus

    @Before
    fun setUp() {
        eventBus = DownloadEventBus()
    }

    @Test
    fun addAndRemoveInfoListener() {
        var callCount = 0
        val listener = object : FakeDownloadInfoListener() {
            override fun onUpdateLabels() {
                callCount++
            }
        }

        eventBus.addDownloadInfoListener(listener)
        eventBus.forEachListener { it.onUpdateLabels() }
        assertEquals(1, callCount)

        eventBus.removeDownloadInfoListener(listener)
        eventBus.forEachListener { it.onUpdateLabels() }
        assertEquals(1, callCount) // no additional call
    }

    @Test
    fun forEachListener_skipsGarbageCollectedRefs() {
        var strongCallCount = 0
        val strongListener = object : FakeDownloadInfoListener() {
            override fun onUpdateLabels() {
                strongCallCount++
            }
        }

        // Add a listener that we will lose the strong reference to
        var weakListener: FakeDownloadInfoListener? = object : FakeDownloadInfoListener() {
            override fun onUpdateLabels() {
                fail("GC'd listener should not be called")
            }
        }
        eventBus.addDownloadInfoListener(weakListener!!)
        eventBus.addDownloadInfoListener(strongListener)

        // Lose the strong reference and request GC
        @Suppress("UNUSED_VALUE")
        weakListener = null
        System.gc()
        System.runFinalization()

        eventBus.forEachListener { it.onUpdateLabels() }
        assertEquals(1, strongCallCount)
    }

    @Test
    fun setAndGetDownloadListener() {
        assertNull(eventBus.getDownloadListener())

        val listener = object : DownloadListener {
            override fun onGet509() {}
            override fun onStart(info: DownloadInfo) {}
            override fun onDownload(info: DownloadInfo) {}
            override fun onGetPage(info: DownloadInfo) {}
            override fun onFinish(info: DownloadInfo) {}
            override fun onCancel(info: DownloadInfo) {}
        }

        eventBus.setDownloadListener(listener)
        assertSame(listener, eventBus.getDownloadListener())

        eventBus.setDownloadListener(null)
        assertNull(eventBus.getDownloadListener())
    }

    @Test
    fun registeredListeners_areHeldInOrder() {
        val listenerA = FakeDownloadInfoListener()
        val listenerB = FakeDownloadInfoListener()

        eventBus.addDownloadInfoListener(listenerA)
        eventBus.addDownloadInfoListener(listenerB)

        val refs = eventBus.mDownloadInfoListeners
        assertEquals(2, refs.size)
        assertSame(listenerA, refs[0].get())
        assertSame(listenerB, refs[1].get())
    }

    @Test
    fun multipleListeners_allNotified() {
        val callCounts = IntArray(5)
        val listeners = List(5) { index ->
            object : FakeDownloadInfoListener() {
                override fun onUpdateLabels() {
                    callCounts[index]++
                }
            }
        }

        for (listener in listeners) {
            eventBus.addDownloadInfoListener(listener)
        }

        eventBus.forEachListener { it.onUpdateLabels() }

        for (i in callCounts.indices) {
            assertEquals("Listener $i should have been called once", 1, callCounts[i])
        }
    }

    @Test
    fun removeListener_onlyRemovesSpecificOne() {
        var callCountA = 0
        var callCountB = 0

        val listenerA = object : FakeDownloadInfoListener() {
            override fun onUpdateLabels() {
                callCountA++
            }
        }
        val listenerB = object : FakeDownloadInfoListener() {
            override fun onUpdateLabels() {
                callCountB++
            }
        }

        eventBus.addDownloadInfoListener(listenerA)
        eventBus.addDownloadInfoListener(listenerB)

        eventBus.removeDownloadInfoListener(listenerA)

        eventBus.forEachListener { it.onUpdateLabels() }

        assertEquals(0, callCountA)
        assertEquals(1, callCountB)
    }

    @Test
    fun postToMain_executesBlockOnMainThread() {
        var executed = false

        eventBus.postToMain {
            executed = true
        }

        // Block is posted; idle the looper to execute it
        assertFalse(executed)
        ShadowLooper.idleMainLooper()
        assertTrue(executed)
    }

    /**
     * Open no-op implementation of [DownloadInfoListener] for test use.
     */
    private open class FakeDownloadInfoListener : DownloadInfoListener {
        override fun onReplace(newInfo: DownloadInfo, oldInfo: DownloadInfo) {}
        override fun onChange() {}
        override fun onRenameLabel(from: String, to: String) {}
        override fun onUpdateLabels() {}
    }
}
