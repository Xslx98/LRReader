package com.lanraragi.reader.ui.scene.gallery.list

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.lanraragi.reader.R
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * View-layer half of the "merge into tankoubon" choreography (spec
 * 2026-09-22 §4): dims the succeeded rows, flies snapshots of their covers
 * (plus a "+N" pill for the rest) into the destination cover, applies the
 * planner's end state (row removals / provisional insert, through [Host])
 * once the flyers have clearly left, then pulses the destination with an
 * expanding ring and reports completion.
 *
 * Flyers steer toward the destination's LIVE rect on every frame, so a
 * destination that slides up during the close-up (or only materializes as
 * the provisional row after the insert) is still hit.
 *
 * One instance per run. [cancel] (scene stop, view destroy, a page reload)
 * tears every animator down and still applies the end state. Reduced motion
 * (animator scale 0, which is also what "remove animations" sets) skips
 * straight to the end state.
 */
internal class TankMergeAnimator(
    private val root: ViewGroup,
    private val recyclerView: RecyclerView,
    private val host: Host,
) {

    interface Host {
        /** Cover view of the attached holder at [position] (CURRENT adapter coordinates), or null. */
        fun thumbViewAt(position: Int): View?

        /** Row view of the attached holder at [position], or null. */
        fun rowViewAt(position: Int): View?

        /** Mutates the data helper: removals (descending) then the provisional insert. Called once. */
        fun applyEndState()

        /** The batch card's Tank button, the fallback landing spot, or null when the card is gone. */
        fun batchButtonView(): View?

        /** The whole batch card; covers under it are not snapshotted (they would carry its pixels). */
        fun batchBarView(): View?
    }

    private val animators = mutableListOf<Animator>()
    private val overlayViews = mutableListOf<View>()
    private val overlayDrawables = mutableListOf<Drawable>()
    private val dimmedRows = mutableListOf<View>()
    private var touchBlocker: View? = null
    private var endStateApplied = false
    private var finished = false
    private var cancelling = false
    private var onDone: (() -> Unit)? = null
    private var pendingScrollListener: RecyclerView.OnScrollListener? = null

    /** Live destination cover once resolved; flyers re-read its rect each frame. */
    private var destinationView: View? = null

    /** Where the flyers aim before the destination view exists (provisional row) or when none will. */
    private var predictedTarget: Rect? = null

    private var plan: TankMergePlanner.MergePlan? = null
    private var pulseView: View? = null
    private var flyersInFlight = 0
    private var landed = false

    private val density = root.resources.displayMetrics.density
    private val liftPx = LIFT_DP * density
    private val flyInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    fun start(plan: TankMergePlanner.MergePlan, onDone: () -> Unit) {
        check(this.onDone == null) { "TankMergeAnimator runs once" }
        this.onDone = onDone
        this.plan = plan
        if (!ValueAnimator.areAnimatorsEnabled()) {
            applyEndStateOnce()
            finish()
            return
        }
        recyclerView.stopScroll()
        blockTouch()
        when (val dest = plan.destination) {
            is TankMergePlanner.Destination.Row -> {
                if (dest.needsScroll) {
                    scrollThenLaunch(dest.position, plan)
                    return
                }
                destinationView = host.thumbViewAt(dest.position)
                if (destinationView == null) predictedTarget = fallbackRect()
            }
            TankMergePlanner.Destination.ProvisionalRow -> {
                // The provisional row lands at the topmost member's index, i.e.
                // roughly where that member's cover is right now.
                predictedTarget = plan.provisionalInsertAt
                    ?.let { host.thumbViewAt(it) }
                    ?.let { rectInRoot(it) }
                    ?: fallbackRect()
            }
            TankMergePlanner.Destination.BatchButton -> predictedTarget = fallbackRect()
        }
        launch(plan)
    }

    /** Cancels everything, applies the end state and completes. Safe to call repeatedly. */
    fun cancel() {
        if (finished) return
        cancelling = true
        pendingScrollListener?.let { recyclerView.removeOnScrollListener(it) }
        pendingScrollListener = null
        animators.toList().forEach { it.cancel() }
        animators.clear()
        clearOverlay()
        dimmedRows.forEach { it.alpha = 1f }
        dimmedRows.clear()
        pulseView?.let { it.scaleX = 1f; it.scaleY = 1f }
        applyEndStateOnce()
        finish()
    }

    // ─── Phases ──────────────────────────────────────────────────────────

    private fun scrollThenLaunch(position: Int, plan: TankMergePlanner.MergePlan) {
        val proceed = object : Runnable {
            var done = false
            override fun run() {
                if (done || finished) return
                done = true
                pendingScrollListener?.let { recyclerView.removeOnScrollListener(it) }
                pendingScrollListener = null
                destinationView = host.thumbViewAt(position)
                if (destinationView == null) predictedTarget = fallbackRect()
                launch(plan)
            }
        }
        val listener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) proceed.run()
            }
        }
        pendingScrollListener = listener
        recyclerView.addOnScrollListener(listener)
        recyclerView.smoothScrollToPosition(position)
        root.postDelayed(proceed, SCROLL_TIMEOUT_MS)
    }

    /**
     * Covers are hardware bitmaps (ImageDecoder), which a software canvas
     * cannot draw, so the flyers are cut from a [PixelCopy] of the scene
     * root taken BEFORE the rows dim. The copy is asynchronous but lands
     * within a frame; a failed copy just means no cover flyers.
     */
    private fun launch(plan: TankMergePlanner.MergePlan) {
        if (finished) return
        val window = (root.context as? Activity)?.window
        if (window == null || root.width <= 0 || root.height <= 0 || plan.flyerPositions.isEmpty()) {
            launchFlyers(plan, snapshot = null)
            return
        }
        val snapshot = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.getLocationInWindow(tmpRootLoc)
        val src = Rect(tmpRootLoc[0], tmpRootLoc[1], tmpRootLoc[0] + root.width, tmpRootLoc[1] + root.height)
        runCatching {
            PixelCopy.request(window, src, snapshot, { result ->
                if (!finished) launchFlyers(plan, snapshot.takeIf { result == PixelCopy.SUCCESS })
            }, root.handler ?: Handler(Looper.getMainLooper()))
        }.onFailure { launchFlyers(plan, snapshot = null) }
    }

    private fun launchFlyers(plan: TankMergePlanner.MergePlan, snapshot: Bitmap?) {
        if (finished) return
        dimRows(plan)
        var index = 0
        var folded = plan.extraCount
        if (snapshot != null) {
            val covered = host.batchBarView()?.takeIf { it.isVisible }?.let { rectInRoot(it) }
            plan.flyerPositions.mapNotNull { host.thumbViewAt(it) }.forEach { thumb ->
                val from = rectInRoot(thumb)
                val flyer = if (covered != null && Rect.intersects(from, covered)) null else makeCoverFlyer(snapshot, from)
                if (flyer == null) {
                    folded++
                    return@forEach
                }
                fly(flyer, from, startDelay = FLYER_STAGGER_MS * index)
                index++
            }
        } else {
            folded += plan.flyerPositions.size
        }
        if (folded > 0) {
            val origin = host.batchButtonView()?.let { rectInRoot(it) } ?: bottomCenterRect()
            fly(makePill(folded, origin), origin, startDelay = FLYER_STAGGER_MS * index)
        }
        root.postDelayed({ closeUp(plan) }, CLOSE_UP_DELAY_MS)
        if (flyersInFlight == 0) {
            // Nothing to fly (covers not laid out): land as soon as the close-up starts.
            root.postDelayed({ land() }, CLOSE_UP_DELAY_MS)
        }
    }

    private fun closeUp(plan: TankMergePlanner.MergePlan) {
        if (finished || endStateApplied) return
        applyEndStateOnce()
        val dest = plan.destination
        val insertAt = plan.provisionalInsertAt
        if (dest is TankMergePlanner.Destination.ProvisionalRow && insertAt != null) {
            recyclerView.doOnPreDraw {
                if (finished) return@doOnPreDraw
                destinationView = host.thumbViewAt(insertAt)
                host.rowViewAt(insertAt)?.let { materialize(it) }
            }
        } else if (dest is TankMergePlanner.Destination.Row) {
            // Rows above the tank slid up: the holder normally survives the
            // move, but re-resolve at its post-removal position if it did not.
            val shifted = dest.position - plan.removals.count { it < dest.position }
            recyclerView.doOnPreDraw {
                if (finished) return@doOnPreDraw
                if (destinationView?.isAttachedToWindow != true) destinationView = host.thumbViewAt(shifted)
            }
        }
    }

    private fun land() {
        if (finished || cancelling || landed) return
        landed = true
        if (plan?.removals.isNullOrEmpty()) {
            // Ungrouped mode keeps the rows: bring them back.
            dimmedRows.forEach { row ->
                track(ObjectAnimator.ofFloat(row, View.ALPHA, row.alpha, 1f).setDuration(DIM_MS)).start()
            }
            dimmedRows.clear()
        }
        val target = destinationView?.takeIf { it.isAttachedToWindow }
        if (target == null) {
            finish()
            return
        }
        pulseView = target
        val up = ObjectAnimator.ofPropertyValuesHolder(
            target,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, PULSE_SCALE),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, PULSE_SCALE),
        ).setDuration(PULSE_UP_MS)
        val down = ObjectAnimator.ofPropertyValuesHolder(
            target,
            PropertyValuesHolder.ofFloat(View.SCALE_X, PULSE_SCALE, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, PULSE_SCALE, 1f),
        ).setDuration(PULSE_DOWN_MS).apply { interpolator = OvershootInterpolator() }
        track(AnimatorSet().apply { playSequentially(up, down) }).start()
        ring(rectInRoot(target))
    }

    private fun ring(around: Rect) {
        val ring = RingDrawable(density)
        val maxRadius = max(around.width(), around.height()) / 2f * RING_RADIUS_FACTOR
        ring.setBounds(
            (around.exactCenterX() - maxRadius).toInt(),
            (around.exactCenterY() - maxRadius).toInt(),
            (around.exactCenterX() + maxRadius).toInt(),
            (around.exactCenterY() + maxRadius).toInt(),
        )
        root.overlay.add(ring)
        overlayDrawables += ring
        val anim = ValueAnimator.ofFloat(0f, 1f).setDuration(RING_MS)
        anim.interpolator = LinearInterpolator()
        anim.addUpdateListener { a ->
            val p = a.animatedValue as Float
            ring.radius = maxRadius * p
            ring.alpha = ((1f - p) * RING_START_ALPHA * ALPHA_MAX).toInt()
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                root.overlay.remove(ring)
                overlayDrawables -= ring
                animators -= animation
                if (!finished && !cancelling) finish()
            }
        })
        track(anim).start()
    }

    private fun finish() {
        if (finished) return
        finished = true
        unblockTouch()
        clearOverlay()
        val cb = onDone
        onDone = null
        cb?.invoke()
    }

    // ─── Flyers ──────────────────────────────────────────────────────────

    private fun makeCoverFlyer(snapshot: Bitmap, from: Rect): View? {
        val clipped = Rect(from)
        if (!clipped.intersect(0, 0, snapshot.width, snapshot.height) || clipped.isEmpty) return null
        val bitmap = runCatching {
            Bitmap.createBitmap(snapshot, clipped.left, clipped.top, clipped.width(), clipped.height())
        }.getOrNull() ?: return null
        from.set(clipped)
        return ImageView(root.context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
        }
    }

    private fun makePill(count: Int, origin: Rect): View {
        val accent = TypedValue().let { tv ->
            if (root.context.theme.resolveAttribute(R.attr.widgetColorThemeAccent, tv, true)) tv.data
            else Color.DKGRAY
        }
        return TextView(root.context).apply {
            text = "+$count"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, PILL_TEXT_SP)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isSingleLine = true
            val padH = (PILL_PAD_H_DP * density).toInt()
            val padV = (PILL_PAD_V_DP * density).toInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = PILL_CORNER_DP * density
                setColor(accent)
            }
            measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            // Sized by content (+ slack so the exact re-measure never wraps);
            // the origin rect only supplies the start centre.
            val halfW = measuredWidth / 2 + PILL_SLACK_PX
            val halfH = measuredHeight / 2
            origin.set(
                origin.centerX() - halfW, origin.centerY() - halfH,
                origin.centerX() + halfW, origin.centerY() + halfH,
            )
        }
    }

    private fun fly(flyer: View, from: Rect, startDelay: Long) {
        val w = from.width()
        val h = from.height()
        flyer.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        flyer.layout(0, 0, w, h)
        flyer.pivotX = w / 2f
        flyer.pivotY = h / 2f
        flyer.x = from.left.toFloat()
        flyer.y = from.top.toFloat()
        root.overlay.add(flyer)
        overlayViews += flyer
        flyersInFlight++

        val startCx = from.exactCenterX()
        val startCy = from.exactCenterY()
        val anim = ValueAnimator.ofFloat(0f, 1f).setDuration(FLY_MS)
        anim.startDelay = startDelay
        anim.interpolator = LinearInterpolator()
        anim.addUpdateListener { a ->
            val p = a.animatedValue as Float
            val e = flyInterpolator.getInterpolation(p)
            val target = currentTarget()
            val cx = startCx + (target.exactCenterX() - startCx) * e
            val cy = startCy + (target.exactCenterY() - startCy) * e - liftPx * sin(PI * p).toFloat()
            flyer.x = cx - w / 2f
            flyer.y = cy - h / 2f
            val scale = 1f + (FLYER_END_SCALE - 1f) * e
            flyer.scaleX = scale
            flyer.scaleY = scale
            flyer.alpha = 1f + (FLYER_END_ALPHA - 1f) * e
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                root.overlay.remove(flyer)
                overlayViews -= flyer
                animators -= animation
                flyersInFlight--
                if (flyersInFlight == 0 && !finished && !cancelling) land()
            }
        })
        track(anim).start()
    }

    private fun currentTarget(): Rect =
        destinationView?.takeIf { it.isAttachedToWindow }?.let { rectInRoot(it) }
            ?: predictedTarget
            ?: fallbackRect()

    private fun materialize(row: View) {
        row.scaleX = MATERIALIZE_SCALE
        row.scaleY = MATERIALIZE_SCALE
        val anim = ObjectAnimator.ofPropertyValuesHolder(
            row,
            PropertyValuesHolder.ofFloat(View.SCALE_X, MATERIALIZE_SCALE, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, MATERIALIZE_SCALE, 1f),
        ).setDuration(MATERIALIZE_MS)
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                row.scaleX = 1f
                row.scaleY = 1f
                animators -= animation
            }
        })
        track(anim).start()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun dimRows(plan: TankMergePlanner.MergePlan) {
        (plan.removals + plan.flyerPositions).distinct().forEach { pos ->
            val row = host.rowViewAt(pos) ?: return@forEach
            dimmedRows += row
            track(ObjectAnimator.ofFloat(row, View.ALPHA, row.alpha, DIM_ALPHA).setDuration(DIM_MS)).start()
        }
    }

    private fun applyEndStateOnce() {
        if (endStateApplied) return
        endStateApplied = true
        host.applyEndState()
    }

    private fun track(animator: Animator): Animator {
        animators += animator
        return animator
    }

    private fun blockTouch() {
        val blocker = View(root.context).apply {
            isClickable = true
            setOnTouchListener { _, _ -> true }
        }
        root.addView(
            blocker,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        touchBlocker = blocker
    }

    private fun unblockTouch() {
        touchBlocker?.let { root.removeView(it) }
        touchBlocker = null
    }

    private fun clearOverlay() {
        overlayViews.forEach { root.overlay.remove(it) }
        overlayViews.clear()
        overlayDrawables.forEach { root.overlay.remove(it) }
        overlayDrawables.clear()
        flyersInFlight = 0
    }

    private fun fallbackRect(): Rect = host.batchButtonView()?.let { rectInRoot(it) } ?: bottomCenterRect()

    private fun bottomCenterRect(): Rect {
        val size = (FALLBACK_TARGET_DP * density).toInt()
        val cx = root.width / 2
        val cy = root.height - size
        return Rect(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
    }

    private val tmpLoc = IntArray(2)
    private val tmpRootLoc = IntArray(2)

    private fun rectInRoot(v: View): Rect {
        v.getLocationInWindow(tmpLoc)
        root.getLocationInWindow(tmpRootLoc)
        val left = tmpLoc[0] - tmpRootLoc[0]
        val top = tmpLoc[1] - tmpRootLoc[1]
        return Rect(left, top, left + v.width, top + v.height)
    }

    /** 1 dp stroke circle that [ring] expands and fades from the destination centre. */
    private class RingDrawable(density: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = Color.WHITE
        }
        var radius = 0f
            set(value) { field = value; invalidateSelf() }

        override fun draw(canvas: Canvas) {
            if (radius <= 0f) return
            canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), radius, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val FLYER_STAGGER_MS = 40L
        const val FLY_MS = 380L
        const val CLOSE_UP_DELAY_MS = 120L
        const val DIM_MS = 120L
        const val DIM_ALPHA = 0.35f
        const val LIFT_DP = 24f
        const val FLYER_END_SCALE = 0.32f
        const val FLYER_END_ALPHA = 0.85f
        const val PULSE_SCALE = 1.08f
        const val PULSE_UP_MS = 140L
        const val PULSE_DOWN_MS = 200L
        const val RING_MS = 320L
        const val RING_RADIUS_FACTOR = 1.4f
        const val RING_START_ALPHA = 0.6f
        const val ALPHA_MAX = 255
        const val MATERIALIZE_SCALE = 0.92f
        const val MATERIALIZE_MS = 220L
        const val SCROLL_TIMEOUT_MS = 300L
        const val PILL_TEXT_SP = 13f
        const val PILL_PAD_H_DP = 10f
        const val PILL_PAD_V_DP = 4f
        const val PILL_CORNER_DP = 999f
        const val PILL_SLACK_PX = 2
        const val FALLBACK_TARGET_DP = 48f
    }
}
