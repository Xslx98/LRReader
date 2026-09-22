package com.lanraragi.reader.event

/**
 * A best-effort tank tag materialization (spec 2026-09-22 §5.3) failed
 * AFTER the membership write succeeded. Membership is not rolled back;
 * the shell surfaces a Snackbar naming the tank.
 */
data class TankTagSyncFailedEvent(val tankId: String, val tankName: String)
