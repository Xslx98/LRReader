package com.lanraragi.reader.event

/**
 * Emitted after the app changed an archive's cover on the server
 * (`PUT /api/archives/{id}/thumbnail`). [com.lanraragi.reader.client.ArchiveCoverStamps]
 * has already been bumped, so subscribers just re-bind the cover for
 * [arcid]: the new key and bust URL fetch the fresh picture.
 */
data class ArchiveCoverChangedEvent(val arcid: String)
