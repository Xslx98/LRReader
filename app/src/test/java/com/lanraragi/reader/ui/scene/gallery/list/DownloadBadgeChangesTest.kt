package com.lanraragi.reader.ui.scene.gallery.list

import com.lanraragi.reader.download.DownloadLoadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** [downloadBadgeChanges]: when the gallery list re-binds its "downloaded" badges. */
class DownloadBadgeChangesTest {

    @Test
    fun theLoadEndingIsAChange_evenWhenRoomAlreadyHadTheSet() = runTest(UnconfinedTestDispatcher()) {
        val arcids = MutableStateFlow(setOf("a", "b"))
        val load = MutableStateFlow<DownloadLoadState>(DownloadLoadState.Loading)
        var changes = 0
        val job = downloadBadgeChanges(arcids, load).onEach { changes++ }.launchIn(this)

        assertEquals("the bound state is not a change", 0, changes)
        load.value = DownloadLoadState.Loaded
        assertEquals(1, changes)

        arcids.value = setOf("a", "b", "c")
        assertEquals(2, changes)
        arcids.value = setOf("a", "b", "c")
        assertEquals("same set, no re-bind", 2, changes)
        job.cancel()
    }

    @Test
    fun aRetryGoingBackToLoadingAndEndingAgain_rebindsOnItsEnd() = runTest(UnconfinedTestDispatcher()) {
        val arcids = MutableStateFlow(setOf("a"))
        val load = MutableStateFlow<DownloadLoadState>(DownloadLoadState.Failed(IllegalStateException()))
        var changes = 0
        val job = downloadBadgeChanges(arcids, load).onEach { changes++ }.launchIn(this)

        load.value = DownloadLoadState.Loading
        load.value = DownloadLoadState.Loaded

        assertEquals(2, changes)
        job.cancel()
    }
}
