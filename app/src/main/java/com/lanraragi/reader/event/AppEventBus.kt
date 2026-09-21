package com.lanraragi.reader.event

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

    // Non-sticky: each delete is a one-shot signal — late subscribers should not
    // re-process old deletions. Generous extraBufferCapacity so we never drop on
    // burst (multi-select delete in the future).
    private val _archiveDeletedEvent = MutableSharedFlow<ArchiveDeletedEvent>(
        extraBufferCapacity = 128
    )
    val archiveDeletedEvent = _archiveDeletedEvent.asSharedFlow()

    fun postArchiveDeletedEvent(event: ArchiveDeletedEvent) {
        _archiveDeletedEvent.tryEmit(event)
    }

    // Non-sticky one-shot like archiveDeletedEvent: the detail page's tank
    // membership editor reports joined/left tanks so covered list scenes can
    // play the merge choreography (or reload) once they are back on screen.
    private val _tankMembershipChangedEvent = MutableSharedFlow<TankMembershipChangedEvent>(
        extraBufferCapacity = 32
    )
    val tankMembershipChangedEvent = _tankMembershipChangedEvent.asSharedFlow()

    fun postTankMembershipChangedEvent(event: TankMembershipChangedEvent) {
        _tankMembershipChangedEvent.tryEmit(event)
    }

}
