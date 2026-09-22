package com.lanraragi.reader.event

/**
 * Emitted after an archive's rating was saved on the server. List scenes
 * covered by the detail page update that row in place.
 *
 * Replaces the scene-result round trip (detail → list via `setResult`),
 * which was lost whenever the Activity was recreated in between: scene
 * result requests are not part of the saved state.
 */
data class ArchiveRatingChangedEvent(val arcid: String, val rating: Float)
