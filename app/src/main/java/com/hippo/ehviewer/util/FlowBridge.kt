@file:JvmName("FlowBridge")
package com.hippo.ehviewer.util

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.hippo.ehviewer.ServiceRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.function.Consumer

/**
 * Bridge for [LifecycleOwner] classes (Fragments, Activities) to collect
 * a Kotlin [Flow] with lifecycle-aware cancellation.
 *
 * Collection is scoped to [owner]'s lifecycle — automatically cancelled when
 * the lifecycle reaches DESTROYED. The [consumer] receives each emission on
 * the main thread (default dispatcher for [lifecycleScope]).
 *
 * Convention: Scene collectors pass `viewLifecycleOwner` — the scene
 * framework detaches covered scenes (view destroyed, fragment alive), so a
 * fragment-scoped collector registered from `onCreateView`/`onViewCreated`
 * stacks a duplicate on every re-attach.
 *
 * Usage from a Scene:
 * ```kotlin
 * collectFlow(viewLifecycleOwner, viewModel.downloads) { downloads ->
 *     // handle new list on UI thread
 * }
 * ```
 */
fun <T> collectFlow(owner: LifecycleOwner, flow: Flow<T>, consumer: Consumer<T>) {
    owner.lifecycleScope.launch(
        ServiceRegistry.coroutineModule.exceptionHandler
    ) {
        flow.collect { consumer.accept(it) }
    }
}

/**
 * Escape hatch: collects for [owner]'s entire lifetime (until DESTROYED),
 * including while STOPPED.
 *
 * Use ONLY for collectors whose event loss would corrupt a state machine or
 * lose data (e.g. loading-state transitions, the gallery-list deletion
 * buffer). Every call site must carry a comment justifying the choice.
 * When [owner] is a Fragment (not its view owner), the consumer must not
 * assume a live view.
 */
fun <T> collectFlowWhileCreated(owner: LifecycleOwner, flow: Flow<T>, consumer: Consumer<T>) {
    owner.lifecycleScope.launch(
        ServiceRegistry.coroutineModule.exceptionHandler
    ) {
        flow.collect { consumer.accept(it) }
    }
}
