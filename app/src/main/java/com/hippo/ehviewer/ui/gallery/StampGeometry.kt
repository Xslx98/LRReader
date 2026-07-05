package com.hippo.ehviewer.ui.gallery

import java.util.Locale

/**
 * Pure math for stamp positions. Server format: "x,y" percentages (0-100,
 * floats) relative to the page IMAGE (not the screen). Kept free of
 * android.graphics types so it unit-tests on the JVM.
 */
object StampGeometry {

    /** Fallback for malformed positions: page center (renders, deletable, drag heals it). */
    const val FALLBACK_NORM = 50f

    fun parsePosition(position: String): Pair<Float, Float>? {
        val parts = position.split(',')
        if (parts.size != 2) return null
        val x = parts[0].trim().toFloatOrNull() ?: return null
        val y = parts[1].trim().toFloatOrNull() ?: return null
        return x.coerceIn(0f, 100f) to y.coerceIn(0f, 100f)
    }

    fun formatPosition(normX: Float, normY: Float): String {
        // NaN would otherwise survive coerceIn and emit the literal "NaN,NaN" onto the wire.
        val x = if (normX.isNaN()) FALLBACK_NORM else normX.coerceIn(0f, 100f)
        val y = if (normY.isNaN()) FALLBACK_NORM else normY.coerceIn(0f, 100f)
        return String.format(Locale.US, "%.2f,%.2f", x, y)
    }

    /** norm (0-100) -> screen coordinate along one axis of the image rect. */
    fun normToScreen(norm: Float, rectStart: Float, rectSize: Float): Float =
        rectStart + norm / 100f * rectSize

    /** screen -> norm (0-100), clamped into the image rect (drag semantics). */
    fun screenToNorm(screen: Float, rectStart: Float, rectSize: Float): Float {
        // !(x > 0f) is intentionally NaN-safe: catches NaN, 0, and negatives
        // (NaN comparisons are always false, so `rectSize <= 0f` would let NaN through).
        if (!(rectSize > 0f)) return FALLBACK_NORM
        return ((screen - rectStart) / rectSize * 100f).coerceIn(0f, 100f)
    }
}
