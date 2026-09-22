package com.lanraragi.reader.event

/**
 * A best-effort tank follow-up (spec 2026-09-22 §5.3 tag materialization
 * or §6 category promotion) failed AFTER the membership write succeeded.
 * Membership is not rolled back; the shell surfaces a Snackbar naming the
 * tank and what could not be updated.
 */
data class TankTagSyncFailedEvent(
    val tankId: String,
    val tankName: String,
    val kind: Kind = Kind.TAGS,
) {
    enum class Kind { TAGS, CATEGORIES }
}
