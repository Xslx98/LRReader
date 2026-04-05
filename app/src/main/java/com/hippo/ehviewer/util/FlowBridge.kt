@file:JvmName("FlowBridge")
package com.hippo.ehviewer.util

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.hippo.ehviewer.ServiceRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.function.Consumer

/**
 * Bridge for Java [LifecycleOwner] classes (Fragments, Activities) to collect
 * a Kotlin [Flow] with lifecycle-aware cancellation.
 *
 * Collection is scoped to [owner]'s lifecycle — automatically cancelled when
 * the lifecycle reaches DESTROYED. The [consumer] receives each emission on
 * the main thread (default dispatcher for [lifecycleScope]).
 *
 * Usage from Java:
 * ```java
 * FlowBridge.collectFlow(getViewLifecycleOwner(), EhDB.observeDownloads(), downloads -> {
 *     // handle new list on UI thread
 * });
 * ```
 */
fun <T> collectFlow(owner: LifecycleOwner, flow: Flow<T>, consumer: Consumer<T>) {
    owner.lifecycleScope.launch(
        ServiceRegistry.coroutineModule.exceptionHandler
    ) {
        flow.collect { consumer.accept(it) }
    }
}
