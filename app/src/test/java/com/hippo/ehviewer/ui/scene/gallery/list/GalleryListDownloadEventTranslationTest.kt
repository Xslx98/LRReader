package com.hippo.ehviewer.ui.scene.gallery.list

import com.hippo.ehviewer.dao.DownloadInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Translation contract for the gallery-list download listener.
 *
 * The gallery list's only download-dependent pixel is the "downloaded" badge,
 * which reads membership (`containDownloadInfo`) — never per-page progress.
 * Per-page [DownloadInfoListener.onUpdate] callbacks (several per second on
 * LAN + a 2 s speed tick) therefore must NOT reach the adapter: they used to
 * trigger an O(N) scan plus a full row rebind that cancelled and restarted
 * the row's thumbnail request for zero visual change.
 *
 * Membership-changing callbacks (add/remove/reload/change) emit [
 * GalleryListViewModel.DownloadEvent.BulkChanged]; everything else is silent.
 */
class GalleryListDownloadEventTranslationTest {

    private val emitted = mutableListOf<GalleryListViewModel.DownloadEvent>()
    private val listener = galleryListDownloadEventListener { emitted.add(it) }
    private val info = DownloadInfo().apply { arcid = "abc" }

    @Test
    fun `per-page onUpdate emits nothing`() {
        listener.onUpdate(info, emptyList(), emptyList())
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `onUpdateAll emits nothing`() {
        listener.onUpdateAll()
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `membership changes emit BulkChanged`() {
        listener.onAdd(info, emptyList(), 0)
        listener.onRemove(info, emptyList(), 0)
        listener.onReload()
        listener.onChange()
        assertEquals(
            List(4) { GalleryListViewModel.DownloadEvent.BulkChanged },
            emitted.toList(),
        )
    }

    @Test
    fun `label and replace callbacks emit nothing`() {
        listener.onReplace(info, info)
        listener.onRenameLabel("a", "b")
        listener.onUpdateLabels()
        assertTrue(emitted.isEmpty())
    }
}
