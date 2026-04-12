package com.hippo.ehviewer.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Application-wide event bus using Kotlin SharedFlow.
 * Replaces GreenRobot EventBus with a lightweight, coroutine-based solution.
 *
 * - galleryActivityEvent uses replay=1 to provide sticky-event semantics:
 *   new collectors immediately receive the most recent event.
 */
object AppEventBus {

    // Sticky event: replay=1 ensures late subscribers get the last emitted value
    private val _galleryActivityEvent = MutableSharedFlow<GalleryActivityEvent>(replay = 1)
    val galleryActivityEvent = _galleryActivityEvent.asSharedFlow()

    fun postGalleryActivityEvent(event: GalleryActivityEvent) {
        _galleryActivityEvent.tryEmit(event)
    }

}
