package com.hippo.ehviewer.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.hippo.android.resource.AttrResources
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.gallery.StampGeometry
import com.hippo.lib.glgallery.PageTransform
import com.lanraragi.reader.client.api.LRRStampApi.StampData
import kotlin.math.hypot

/**
 * Full-screen overlay above the GL reader. Renders stamp markers at their
 * mapped positions and owns marker touch interaction (tap / drag / placement).
 * Coordinates assume the overlay shares its origin with the GalleryView
 * (both fill the same FrameLayout).
 *
 * Consumes a touch ONLY when it lands on a marker or placement mode is
 * active; everything else passes through to the GL view untouched.
 */
class StampOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Callback {
        fun onStampTapped(stamp: StampData, page0: Int, screenX: Float, screenY: Float)
        fun onPlaceRequested(page0: Int, normX: Float, normY: Float)
        fun onStampDropped(stamp: StampData, page0: Int, normX: Float, normY: Float)
    }

    var callback: Callback? = null

    /**
     * Supplies cached stamps for a 0-indexed page; set by GalleryActivity.
     *
     * Contract: must be a cheap, stable cached read (pure map lookup — no
     * fetching, copying, or mapping) since [onDraw] invokes it per visible
     * page on every animation frame. Must never emit stamps with an empty id:
     * the drag-skip in [onDraw] matches on id, so empty ids would collide.
     */
    var stampsProvider: ((page0: Int) -> List<StampData>)? = null

    var transforms: List<PageTransform> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var placing: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val markerRadius = MARKER_RADIUS_DP * density
    private val ringWidth = MARKER_RING_DP * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AttrResources.getAttrColor(context, R.attr.widgetColorThemeAccent)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }

    // Active-touch state
    private var touchStamp: StampData? = null
    private var touchPage0 = 0
    private var touchRect: RectF? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragX = 0f
    private var dragY = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val provider = stampsProvider ?: return
        for (t in transforms) {
            for (stamp in provider(t.index)) {
                if (dragging && stamp.id == touchStamp?.id) continue
                val (cx, cy) = markerCenter(stamp, t.imageRect)
                drawMarker(canvas, cx, cy)
            }
        }
        if (dragging) drawMarker(canvas, dragX, dragY)
    }

    private fun drawMarker(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, markerRadius, ringPaint)
        canvas.drawCircle(cx, cy, markerRadius - ringWidth, fillPaint)
    }

    private fun markerCenter(stamp: StampData, rect: RectF): Pair<Float, Float> {
        val norm = StampGeometry.parsePosition(stamp.position)
            ?: (StampGeometry.FALLBACK_NORM to StampGeometry.FALLBACK_NORM)
        return StampGeometry.normToScreen(norm.first, rect.left, rect.width()) to
            StampGeometry.normToScreen(norm.second, rect.top, rect.height())
    }

    @SuppressLint("ClickableViewAccessibility") // performClick IS called on the tap path (onTouchUp)
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cb = callback ?: run {
            resetTouch()
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                if (placing) return true
                val hit = hitTest(event.x, event.y) ?: return false
                touchStamp = hit.first
                touchPage0 = hit.second.index
                // Intentionally frozen at DOWN for drag stability; an in-flight
                // page animation may drift the live transforms, which is accepted
                // (norms are relative and self-correct next render).
                touchRect = hit.second.imageRect
                dragging = false
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger means this was never a single-finger marker gesture
                // (likely a pinch). Bail out: snap the drag back and forfeit the stream
                // so no corrupted position is ever persisted.
                resetTouch()
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (placing) return true
                if (touchStamp == null) return false
                if (!dragging && hypot(event.x - downX, event.y - downY) > touchSlop) {
                    dragging = true
                }
                if (dragging) {
                    val rect = touchRect ?: return true
                    dragX = event.x.coerceIn(rect.left, rect.right)
                    dragY = event.y.coerceIn(rect.top, rect.bottom)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> return onTouchUp(cb, event)
            MotionEvent.ACTION_CANCEL -> {
                resetTouch()
                return true
            }
        }
        return false
    }

    private fun onTouchUp(cb: Callback, event: MotionEvent): Boolean {
        if (placing) {
            val t = transforms.firstOrNull { it.imageRect.contains(event.x, event.y) }
            if (t != null) {
                cb.onPlaceRequested(
                    t.index,
                    StampGeometry.screenToNorm(event.x, t.imageRect.left, t.imageRect.width()),
                    StampGeometry.screenToNorm(event.y, t.imageRect.top, t.imageRect.height()),
                )
            }
            return true
        }
        val stamp = touchStamp ?: return false
        if (dragging) {
            val rect = touchRect
            if (rect != null) {
                cb.onStampDropped(
                    stamp, touchPage0,
                    StampGeometry.screenToNorm(dragX, rect.left, rect.width()),
                    StampGeometry.screenToNorm(dragY, rect.top, rect.height()),
                )
            }
        } else {
            performClick()
            cb.onStampTapped(stamp, touchPage0, event.x, event.y)
        }
        resetTouch()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** Ends an in-flight drag without dispatching (e.g. after a failed update). */
    fun cancelDrag() = resetTouch()

    private fun resetTouch() {
        touchStamp = null
        touchRect = null
        dragging = false
        invalidate()
    }

    private fun hitTest(x: Float, y: Float): Pair<StampData, PageTransform>? {
        val provider = stampsProvider ?: return null
        val hitRadius = markerRadius * HIT_SLOP_FACTOR
        // Last transform drawn last = topmost; iterate in reverse for hits.
        for (t in transforms.asReversed()) {
            for (stamp in provider(t.index).asReversed()) {
                val (cx, cy) = markerCenter(stamp, t.imageRect)
                if (hypot(x - cx, y - cy) <= hitRadius) return stamp to t
            }
        }
        return null
    }

    companion object {
        private const val MARKER_RADIUS_DP = 12f
        private const val MARKER_RING_DP = 2f
        private const val HIT_SLOP_FACTOR = 1.5f
    }
}
