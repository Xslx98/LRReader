/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.hippo.ehviewer.util

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.hippo.ehviewer.ServiceRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Lifecycle contract of the two FlowBridge collectors.
 *
 * [collectFlow] is the safe default: repeatOnLifecycle(STARTED) — collection
 * stops at STOPPED, restarts (with StateFlow replay) at STARTED.
 * [collectFlowWhileCreated] is the explicit escape hatch: collects for the
 * owner's whole lifetime, including while STOPPED, for event flows whose loss
 * would corrupt a state machine (detailLoaded/detailError transitions, the
 * GalleryListScene deletion buffer).
 *
 * Uses a hand-rolled LifecycleRegistry owner — Scene layouts cannot be
 * inflated under Robolectric (Conaco custom views), but FlowBridge is pure
 * lifecycle+flow logic and needs no views.
 */
@RunWith(RobolectricTestRunner::class)
class FlowBridgeLifecycleTest {

    private class TestOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Before
    fun setUp() {
        // FlowBridge launches with ServiceRegistry.coroutineModule.exceptionHandler.
        ServiceRegistry.initializeForTest()
    }

    private fun <T> emit(flow: MutableSharedFlow<T>, value: T) {
        check(flow.tryEmit(value)) { "tryEmit refused $value" }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun collectFlow_deliversWhileStarted() {
        val owner = TestOwner()
        val flow = MutableSharedFlow<Int>(extraBufferCapacity = 4)
        val received = mutableListOf<Int>()
        collectFlow(owner, flow) { received.add(it) }
        owner.moveTo(Lifecycle.State.STARTED)
        emit(flow, 1)
        assertEquals(listOf(1), received)
    }

    @Test
    fun collectFlowWhileCreated_deliversWhileStarted() {
        val owner = TestOwner()
        val flow = MutableSharedFlow<Int>(extraBufferCapacity = 4)
        val received = mutableListOf<Int>()
        collectFlowWhileCreated(owner, flow) { received.add(it) }
        owner.moveTo(Lifecycle.State.STARTED)
        emit(flow, 1)
        assertEquals(listOf(1), received)
    }

    @Test
    fun collectFlowWhileCreated_deliversWhileStopped() {
        val owner = TestOwner()
        val flow = MutableSharedFlow<Int>(extraBufferCapacity = 4)
        val received = mutableListOf<Int>()
        collectFlowWhileCreated(owner, flow) { received.add(it) }
        owner.moveTo(Lifecycle.State.STARTED)
        owner.moveTo(Lifecycle.State.CREATED) // = STOPPED
        emit(flow, 7)
        assertEquals(listOf(7), received)
    }

    @Test
    fun collectFlow_doesNotDeliverBeforeFirstStart() {
        val owner = TestOwner()
        val flow = MutableSharedFlow<Int>(extraBufferCapacity = 4)
        val received = mutableListOf<Int>()
        owner.moveTo(Lifecycle.State.CREATED)
        collectFlow(owner, flow) { received.add(it) }
        shadowOf(Looper.getMainLooper()).idle()
        emit(flow, 1)
        assertEquals(emptyList<Int>(), received)
        owner.moveTo(Lifecycle.State.STARTED)
        emit(flow, 2)
        assertEquals(listOf(2), received)
    }

    @Test
    fun collectFlow_dropsSharedFlowEmissionsWhileStopped() {
        val owner = TestOwner()
        val flow = MutableSharedFlow<Int>(extraBufferCapacity = 4)
        val received = mutableListOf<Int>()
        collectFlow(owner, flow) { received.add(it) }
        owner.moveTo(Lifecycle.State.STARTED)
        emit(flow, 1)
        owner.moveTo(Lifecycle.State.CREATED) // = STOPPED
        emit(flow, 2)
        owner.moveTo(Lifecycle.State.STARTED)
        emit(flow, 3)
        // 2 landed in a stopped window: dropped, not replayed.
        assertEquals(listOf(1, 3), received)
    }

    @Test
    fun collectFlow_replaysLatestStateFlowValueOnRestart() {
        val owner = TestOwner()
        val state = MutableStateFlow(0)
        val received = mutableListOf<Int>()
        collectFlow(owner, state) { received.add(it) }
        owner.moveTo(Lifecycle.State.STARTED)
        assertEquals(listOf(0), received)
        owner.moveTo(Lifecycle.State.CREATED) // = STOPPED
        state.value = 5
        shadowOf(Looper.getMainLooper()).idle()
        owner.moveTo(Lifecycle.State.STARTED)
        // Value changed while stopped is re-delivered by replay on restart.
        assertEquals(listOf(0, 5), received)
    }

    @Test
    fun bothCollectors_cancelAtDestroyed() {
        val owner = TestOwner()
        val flow = MutableSharedFlow<Int>(extraBufferCapacity = 4)
        val viaDefault = mutableListOf<Int>()
        val viaWhileCreated = mutableListOf<Int>()
        collectFlow(owner, flow) { viaDefault.add(it) }
        collectFlowWhileCreated(owner, flow) { viaWhileCreated.add(it) }
        owner.moveTo(Lifecycle.State.STARTED)
        emit(flow, 1)
        owner.moveTo(Lifecycle.State.DESTROYED)
        emit(flow, 2)
        assertEquals(listOf(1), viaDefault)
        assertEquals(listOf(1), viaWhileCreated)
    }
}
