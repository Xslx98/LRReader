package com.hippo.ehviewer.ui.scene.gallery.list

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payload-dispatch contract for the gallery adapter's partial rebind.
 *
 * BulkChanged download events refresh only the "downloaded" membership badge.
 * Dispatching them as a plain notifyItemRangeChanged rebound every holder —
 * including the 20-deep item view cache — and re-ran thumb.load(), cancelling
 * and restarting in-flight thumbnail requests for a one-view visibility flip.
 * With the badge payload the adapter must take the partial-bind path; any
 * unknown payload in the mix must fall back to a full bind (RecyclerView
 * merges payload lists across pending updates).
 */
class GalleryAdapterBadgePayloadTest {

    @Test
    fun `empty payloads means full bind`() {
        assertFalse(GalleryAdapterNew.isBadgeOnlyRebind(emptyList()))
    }

    @Test
    fun `badge payload alone takes the partial path`() {
        assertTrue(GalleryAdapterNew.isBadgeOnlyRebind(listOf(GalleryAdapterNew.PAYLOAD_DOWNLOAD_BADGE)))
    }

    @Test
    fun `merged duplicate badge payloads still take the partial path`() {
        assertTrue(
            GalleryAdapterNew.isBadgeOnlyRebind(
                listOf(GalleryAdapterNew.PAYLOAD_DOWNLOAD_BADGE, GalleryAdapterNew.PAYLOAD_DOWNLOAD_BADGE),
            ),
        )
    }

    @Test
    fun `unknown payload in the mix forces a full bind`() {
        assertFalse(
            GalleryAdapterNew.isBadgeOnlyRebind(
                listOf(GalleryAdapterNew.PAYLOAD_DOWNLOAD_BADGE, Any()),
            ),
        )
    }
}
