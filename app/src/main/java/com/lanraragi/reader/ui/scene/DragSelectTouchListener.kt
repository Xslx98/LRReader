package com.lanraragi.reader.ui.scene

import android.view.Choreographer
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.util.size
import androidx.recyclerview.widget.RecyclerView
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.refreshlayout.RefreshLayout
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Drag-to-select (spec 2026-09-22-drag-select §4): after the host's
 * long-press handler enters multi-select it calls [startDrag]; from then
 * until the finger lifts every row the finger passes joins the selection
 * (range semantics in [DragSelectRange]) and holding the finger in the top
 * or bottom hot zone auto-scrolls the list a little every frame.
 *
 * Attached with [RecyclerView.addOnItemTouchListener]. Idle it intercepts
 * nothing, so taps, scrolls and the library's own press handling are
 * untouched; active it takes the rest of the gesture (RecyclerView cancels
 * its own scroll). EasyRecyclerView 0.1.1 runs its press state machine
 * before `super.onTouchEvent`, but after a performed long press it never
 * fires a click, so the drag cannot double as a tap.
 */
class DragSelectTouchListener(
    private val recyclerView: RecyclerView,
    private val selection: Selection,
) : RecyclerView.OnItemTouchListener {

    /** The host's checked-state port. */
    interface Selection {
        /** Multi-select mode is on. */
        fun isActive(): Boolean
        fun checkedPositions(): Set<Int>
        fun setChecked(position: Int, checked: Boolean)
        /** Rows the drag passes over but never checks (e.g. tank pseudo-rows). */
        fun canSelect(position: Int): Boolean
    }

    private var active = false
    private var anchor = RecyclerView.NO_POSITION
    private var cursor = RecyclerView.NO_POSITION
    private var preDrag: Set<Int> = emptySet()
    private var current: Set<Int> = emptySet()
    private var lastX = 0f
    private var lastY = 0f
    private var scrollStep = 0
    private var scrolling = false

    /** Pull-to-refresh layouts disabled for the drag; re-enabled on end. */
    private val frozenRefreshLayouts = mutableListOf<RefreshLayout>()

    private val density = recyclerView.resources.displayMetrics.density
    private val choreographer = Choreographer.getInstance()
    private val frameCallback = Choreographer.FrameCallback { onScrollFrame() }

    val isDragging: Boolean get() = active

    /**
     * Called by the host from its long-press handler once the pressed row is
     * checked. The pressed row is the anchor; the finger is still down.
     */
    fun startDrag(anchorPosition: Int) {
        if (!selection.isActive() || anchorPosition == RecyclerView.NO_POSITION) return
        active = true
        anchor = anchorPosition
        cursor = anchorPosition
        preDrag = selection.checkedPositions()
        current = preDrag
        holdOffAncestors()
    }

    /**
     * Keep every ancestor out of the gesture. `RefreshLayout` (seven332
     * 0.1.0) implements requestDisallowInterceptTouchEvent as a no-op and
     * would still intercept the first vertical move as a pull — and, being
     * a no-op, it also never forwards the request to ITS parents — so it is
     * disabled for the drag and every ancestor is asked individually.
     */
    private fun holdOffAncestors() {
        var parent = recyclerView.parent
        while (parent is ViewGroup) {
            parent.requestDisallowInterceptTouchEvent(true)
            if (parent is RefreshLayout && parent.isEnabled) {
                parent.isEnabled = false
                frozenRefreshLayouts += parent
            }
            parent = parent.parent
        }
    }

    private fun releaseAncestors() {
        frozenRefreshLayouts.forEach { it.isEnabled = true }
        frozenRefreshLayouts.clear()
        var parent = recyclerView.parent
        while (parent is ViewGroup) {
            parent.requestDisallowInterceptTouchEvent(false)
            parent = parent.parent
        }
    }

    /** Ends an in-flight drag (page reload, view teardown); the selection stays. */
    fun cancel() {
        if (active) endDrag()
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        if (!active) return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            // A new gesture: the drag was started without a matching touch
            // stream (e.g. an accessibility long click). Let it go.
            endDrag()
            return false
        }
        return true
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        if (!active) return
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                lastX = e.x
                lastY = e.y
                updateCursor()
                updateAutoScroll()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit

    // ─── Range ───────────────────────────────────────────────────────────

    private fun updateCursor() {
        val x = lastX.coerceIn(0f, max(0, recyclerView.width - 1).toFloat())
        val y = lastY.coerceIn(0f, max(0, recyclerView.height - 1).toFloat())
        val child = recyclerView.findChildViewUnder(x, y) ?: return
        val position = recyclerView.getChildAdapterPosition(child)
        if (position == RecyclerView.NO_POSITION || position == cursor) return
        cursor = position
        applyRange()
    }

    private fun applyRange() {
        val itemCount = recyclerView.adapter?.itemCount ?: 0
        val target = DragSelectRange.apply(anchor, cursor, preDrag, itemCount, selection::canSelect)
        val diff = DragSelectRange.diff(current, target)
        // Check before un-checking so the count never dips to zero mid-way
        // (zero leaves multi-select mode in every host).
        diff.check.forEach { selection.setChecked(it, true) }
        diff.uncheck.forEach { selection.setChecked(it, false) }
        current = target
    }

    // ─── Edge auto-scroll ────────────────────────────────────────────────

    private fun updateAutoScroll() {
        val height = recyclerView.height
        val zone = max(height * HOT_ZONE_FRACTION, HOT_ZONE_MIN_DP * density)
        val maxStep = MAX_SPEED_DP_PER_FRAME * density
        scrollStep = when {
            lastY < zone -> -(maxStep * (1f - (lastY / zone).coerceIn(0f, 1f))).roundToInt()
            lastY > height - zone -> (maxStep * (1f - ((height - lastY) / zone).coerceIn(0f, 1f))).roundToInt()
            else -> 0
        }
        if (scrollStep != 0 && !scrolling) {
            scrolling = true
            choreographer.postFrameCallback(frameCallback)
        } else if (scrollStep == 0) {
            stopScrolling()
        }
    }

    private fun onScrollFrame() {
        if (!active || scrollStep == 0) {
            scrolling = false
            return
        }
        if (recyclerView.canScrollVertically(if (scrollStep < 0) -1 else 1)) {
            recyclerView.scrollBy(0, scrollStep)
        }
        updateCursor()
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopScrolling() {
        if (!scrolling) return
        scrolling = false
        scrollStep = 0
        choreographer.removeFrameCallback(frameCallback)
    }

    private fun endDrag() {
        active = false
        stopScrolling()
        anchor = RecyclerView.NO_POSITION
        cursor = RecyclerView.NO_POSITION
        preDrag = emptySet()
        current = emptySet()
        releaseAncestors()
    }

    companion object {
        private const val HOT_ZONE_FRACTION = 0.12f
        private const val HOT_ZONE_MIN_DP = 48f
        private const val MAX_SPEED_DP_PER_FRAME = 24f

        /**
         * [Selection] over an [EasyRecyclerView] in custom choice mode; the
         * three multi-select lists all speak this dialect.
         */
        fun forEasyRecyclerView(rv: EasyRecyclerView, canSelect: (Int) -> Boolean): Selection =
            object : Selection {
                override fun isActive(): Boolean = rv.isInCustomChoice
                override fun checkedPositions(): Set<Int> {
                    val states = rv.checkedItemPositions ?: return emptySet()
                    val out = LinkedHashSet<Int>(min(states.size, 64))
                    for (i in 0 until states.size) if (states.valueAt(i)) out += states.keyAt(i)
                    return out
                }
                override fun setChecked(position: Int, checked: Boolean) {
                    if (rv.isItemChecked(position) != checked) rv.setItemChecked(position, checked)
                }
                override fun canSelect(position: Int): Boolean = canSelect(position)
            }
    }
}
