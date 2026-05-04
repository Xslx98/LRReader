package com.hippo.ehviewer.event

/**
 * Emitted after an archive has been successfully deleted on the LANraragi server.
 * Subscribers (e.g. gallery list scenes) should remove any cached references to [arcid]
 * so the UI reflects the deletion without requiring a manual refresh.
 */
data class ArchiveDeletedEvent(val arcid: String)
