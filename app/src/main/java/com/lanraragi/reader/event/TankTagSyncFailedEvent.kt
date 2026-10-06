package com.lanraragi.reader.event

/**
 * A best-effort tank follow-up failed: spec 2026-09-22 §5.3 tag
 * materialization or §6 category promotion AFTER the membership write
 * succeeded, or a local downloaded-tank group write ([Kind.DOWNLOAD_GROUP],
 * audit 2026-10-04 REL-24). Membership is not
 * rolled back; the shell surfaces a Snackbar. [tankName] is empty when the
 * failing step did not know the tank.
 */
data class TankTagSyncFailedEvent(
    val tankId: String,
    val tankName: String,
    val kind: Kind = Kind.TAGS,
) {
    enum class Kind { TAGS, CATEGORIES, DOWNLOAD_GROUP }
}
