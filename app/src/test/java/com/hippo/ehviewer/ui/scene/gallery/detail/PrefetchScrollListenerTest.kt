/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.hippo.ehviewer.ui.scene.gallery.detail

import android.app.Activity
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper

/**
 * Tests for [PrefetchScrollListener]'s dispatch discipline.
 *
 * RecyclerView dispatches `onScrolled` from inside its layout pass
 * (`dispatchLayout` → `dispatchOnScrolled`), where data mutation is
 * forbidden. The production wiring routes [PrefetchScrollListener]'s
 * callback into `PageThumbnailsViewModel.requestPage`, whose synchronous
 * `pageStates` emission inline-resumes the Scene's `Main.immediate`
 * collector and lands in `PageThumbnailAdapter.submitStates` →
 * `notifyItemChanged` — RecyclerView then logs a W-level
 * `IllegalStateException` ("Cannot call this method in a scroll
 * callback"). The listener must therefore post its prefetch dispatch
 * off the scroll-callback frame.
 *
 * The grid geometry mirrors the production working set: 3 columns ×
 * 3 visible rows + 3 prefetch rows = 18 pages (see
 * PageThumbnailCacheBudgetTest).
 */
@RunWith(RobolectricTestRunner::class)
class PrefetchScrollListenerTest {

    private companion object {
        const val SPAN_COUNT = 3
        const val ITEM_HEIGHT_PX = 100
        const val GRID_SIZE_PX = 300
        const val SCROLL_CALLBACK_WARNING = "Cannot call this method in a scroll callback"
    }

    private lateinit var controller: ActivityController<Activity>
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: FixedHeightAdapter

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java)
        controller.create().start().resume().visible()
    }

    @After
    fun tearDown() {
        controller.close()
    }

    /** Attach a [GRID_SIZE_PX]² grid recycler with [itemCount] cells and lay it out. */
    private fun buildGrid(itemCount: Int) {
        val activity = controller.get()
        recycler = RecyclerView(activity)
        adapter = FixedHeightAdapter(itemCount)
        recycler.layoutManager = GridLayoutManager(activity, SPAN_COUNT)
        recycler.adapter = adapter
        activity.setContentView(recycler, ViewGroup.LayoutParams(GRID_SIZE_PX, GRID_SIZE_PX))
        val exactly = View.MeasureSpec.makeMeasureSpec(GRID_SIZE_PX, View.MeasureSpec.EXACTLY)
        recycler.measure(exactly, exactly)
        recycler.layout(0, 0, GRID_SIZE_PX, GRID_SIZE_PX)
    }

    /**
     * Regression pin for the smoke-test logcat warning: a prefetch
     * callback that (transitively) mutates the adapter must not run
     * inside RecyclerView's scroll-callback dispatch, where
     * `assertNotInLayoutOrScroll` logs a W-level IllegalStateException.
     */
    @Test
    fun `prefetch that mutates adapter does not fire inside the scroll callback frame`() {
        buildGrid(itemCount = 60)
        recycler.addOnScrollListener(
            PrefetchScrollListener(SPAN_COUNT) { page ->
                // Mirrors production: requestPage's synchronous state
                // emission ends in notifyItemChanged(page, payload).
                adapter.notifyItemChanged(page, "state")
            }
        )

        recycler.scrollBy(0, ITEM_HEIGHT_PX / 2)
        ShadowLooper.idleMainLooper()

        val scrollCallbackWarnings = ShadowLog.getLogs().filter {
            it.type == Log.WARN && it.tag == "RecyclerView" &&
                it.msg.contains(SCROLL_CALLBACK_WARNING)
        }
        assertTrue(
            "adapter was notified inside RecyclerView's scroll-callback dispatch:\n" +
                scrollCallbackWarnings.joinToString("\n") { it.msg },
            scrollCallbackWarnings.isEmpty(),
        )
    }

    @Test
    fun `dispatch is deferred off the scroll tick and covers viewport plus prefetch rows`() {
        buildGrid(itemCount = 60)
        val pages = mutableListOf<Int>()
        val listener = PrefetchScrollListener(SPAN_COUNT) { pages.add(it) }

        listener.onScrolled(recycler, 0, 0)
        assertTrue(
            "prefetch must not dispatch synchronously from the scroll tick, got $pages",
            pages.isEmpty(),
        )

        ShadowLooper.idleMainLooper()
        // 3 visible rows (0..8) + 3 prefetch rows → pages 0..17.
        assertEquals((0..17).toList(), pages)
    }

    @Test
    fun `scroll ticks in the same frame coalesce into one dispatch`() {
        buildGrid(itemCount = 60)
        val pages = mutableListOf<Int>()
        val listener = PrefetchScrollListener(SPAN_COUNT) { pages.add(it) }

        listener.onScrolled(recycler, 0, 0)
        listener.onScrolled(recycler, 0, 0)
        listener.onScrolled(recycler, 0, 0)
        ShadowLooper.idleMainLooper()

        assertEquals((0..17).toList(), pages)
    }

    @Test
    fun `prefetch window is clamped to the item count`() {
        buildGrid(itemCount = 10)
        val pages = mutableListOf<Int>()
        val listener = PrefetchScrollListener(SPAN_COUNT) { pages.add(it) }

        listener.onScrolled(recycler, 0, 0)
        ShadowLooper.idleMainLooper()

        assertEquals((0..9).toList(), pages)
    }

    private class FixedHeightAdapter(private val count: Int) :
        RecyclerView.Adapter<FixedHeightAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(
                View(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        ITEM_HEIGHT_PX,
                    )
                }
            )

        override fun getItemCount(): Int = count

        override fun onBindViewHolder(holder: VH, position: Int) = Unit

        override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) = Unit
    }
}
