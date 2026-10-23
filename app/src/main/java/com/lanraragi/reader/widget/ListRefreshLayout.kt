package com.lanraragi.reader.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ProgressBar
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.lanraragi.reader.R
import com.lanraragi.reader.util.attrColor
import kotlin.math.max
import kotlin.math.min

/**
 * AndroidX [SwipeRefreshLayout] with a footer: pulling up past the end of
 * the list (or [setFooterRefreshing] from code, e.g. when a scroll reaches
 * the end) loads the next page and shows a progress bar along the bottom
 * edge. Pulling down at the top is the plain SwipeRefreshLayout header.
 * Replaces the seven332 RefreshLayout; the child must be a [RecyclerView].
 */
class ListRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwipeRefreshLayout(context, attrs) {

    interface OnRefreshListener {
        fun onHeaderRefresh()
        fun onFooterRefresh()
    }

    private var listener: OnRefreshListener? = null

    private val footerBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        val accent = ColorStateList.valueOf(context.attrColor(R.attr.widgetColorThemeAccent))
        progressTintList = accent
        indeterminateTintList = accent
        progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        max = PROGRESS_MAX
        isVisible = false
    }

    /** Upward drag of the current gesture while the list is at its end. */
    private var footerPull = 0f
    private var lastTouchY = 0f

    /** The current gesture started with the list at its end (it may pull the footer). */
    private var footerArmed = false

    var isFooterRefreshing = false
        private set

    val isHeaderRefreshing: Boolean
        get() = super.isRefreshing()

    /** Header or footer. */
    val isAnyRefreshing: Boolean
        get() = isHeaderRefreshing || isFooterRefreshing

    override fun onFinishInflate() {
        super.onFinishInflate()
        // Added after the inflated list, which SwipeRefreshLayout takes as
        // its target (its first child that is not the spinner).
        addView(footerBar)
    }

    fun setOnRefreshListener(listener: OnRefreshListener?) {
        this.listener = listener
        super.setOnRefreshListener(listener?.let { l -> SwipeRefreshLayout.OnRefreshListener { l.onHeaderRefresh() } })
    }

    /** Moves the header spinner's start and end positions down by [dy] (e.g. below a search bar). */
    fun offsetHeader(dy: Int) {
        setProgressViewOffset(false, progressViewStartOffset + dy, progressViewEndOffset + dy)
    }

    fun setHeaderRefreshing(refreshing: Boolean) {
        super.setRefreshing(refreshing)
    }

    fun setFooterRefreshing(refreshing: Boolean) {
        isFooterRefreshing = refreshing
        footerPull = 0f
        footerBar.isIndeterminate = refreshing
        footerBar.isVisible = refreshing
    }

    /** The list shows its last pixel (the next page may be loaded). */
    fun isAlmostBottom(): Boolean {
        val list = children.firstOrNull { it is RecyclerView } as RecyclerView? ?: return false
        return list.computeVerticalScrollOffset() >=
            list.computeVerticalScrollRange() - list.computeVerticalScrollExtent()
    }

    private val footerTriggerDistance: Float
        get() = min((parent as View).height * MAX_SWIPE_DISTANCE_FACTOR, REFRESH_TRIGGER_DISTANCE_DP * resources.displayMetrics.density)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        !isFooterRefreshing && super.onInterceptTouchEvent(ev)

    /**
     * The header only takes a gesture that starts with the list at its top
     * (the list starts its nested scroll on the down event), as the seven332
     * RefreshLayout did; SwipeRefreshLayout would otherwise turn the rest of
     * a drag that scrolls back to the top into a pull and reload the list.
     */
    override fun onStartNestedScroll(child: View, target: View, nestedScrollAxes: Int): Boolean =
        !isFooterRefreshing && !target.canScrollVertically(-1) &&
            super.onStartNestedScroll(child, target, nestedScrollAxes)

    /**
     * Watches (never consumes) the touch stream for the footer pull: the
     * list keeps every event, and at its end turns the overscroll into its
     * own edge effect rather than reporting it as nested scroll.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                footerPull = 0f
                lastTouchY = ev.y
                footerArmed = children.firstOrNull { it is RecyclerView }?.canScrollVertically(1) == false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = lastTouchY - ev.y
                lastTouchY = ev.y
                onFooterDrag(dy)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> lastTouchY = ev.y
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseFooterPull()
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * [dy] > 0 drags the content up. Counts only in a gesture that started
     * at the end of the list (as the seven332 RefreshLayout), and only while
     * the list is still there.
     */
    private fun onFooterDrag(dy: Float) {
        if (!footerArmed || !isEnabled || isAnyRefreshing) return
        val list = children.firstOrNull { it is RecyclerView } ?: return
        if (list.canScrollVertically(1)) {
            releaseFooterPull()
            return
        }
        footerPull = max(0f, footerPull + dy)
        val distance = footerTriggerDistance
        if (footerPull >= distance) {
            setFooterRefreshing(true)
            listener?.onFooterRefresh()
        } else {
            footerBar.isIndeterminate = false
            footerBar.progress = (footerPull / distance * PROGRESS_MAX).toInt()
            footerBar.isVisible = footerPull > 0f
        }
    }

    private fun releaseFooterPull() {
        if (!isFooterRefreshing) {
            footerPull = 0f
            footerBar.isVisible = false
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        footerBar.measure(
            MeasureSpec.makeMeasureSpec(measuredWidth - paddingLeft - paddingRight, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val barBottom = measuredHeight - paddingBottom
        footerBar.layout(paddingLeft, barBottom - footerBar.measuredHeight, measuredWidth - paddingRight, barBottom)
    }

    private companion object {
        const val PROGRESS_MAX = 1000
        // The seven332 RefreshLayout's footer trigger: 120 dp, at most 60 % of the parent.
        const val REFRESH_TRIGGER_DISTANCE_DP = 120
        const val MAX_SWIPE_DISTANCE_FACTOR = 0.6f
    }
}
