/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lanraragi.reader.ui.list

/**
 * What a list screen shows, as one value its ViewModel owns: the screen
 * renders it ([com.lanraragi.reader.widget.StateListLayout.render]) and never
 * reads the progress / message / list views back to learn its own state. Kept
 * in the ViewModel, so a rotation after an error shows the error again.
 *
 * Every change goes through [reduce].
 */
sealed interface ListScreenState<out T> {

    /** The items the screen shows in this state (none while loading the first time, empty, or failed with none). */
    val items: List<T>

    /** Loading with nothing to show yet. A reload while items are shown stays [Content]. */
    data object Loading : ListScreenState<Nothing> {
        override val items: List<Nothing> get() = emptyList()
    }

    /** Loaded, and there is nothing. */
    data object Empty : ListScreenState<Nothing> {
        override val items: List<Nothing> get() = emptyList()
    }

    /**
     * The last load failed with [message]. [retained] are the items an earlier
     * load showed: with some, the screen keeps showing them (the failure is
     * only reported); with none, it shows the error and its retry.
     */
    data class Error<T>(val message: String, val retained: List<T>) : ListScreenState<T> {
        override val items: List<T> get() = retained
    }

    /** [items] (never empty) are shown. */
    data class Content<T>(override val items: List<T>) : ListScreenState<T>

    /** What happened to the screen's data; [reduce] turns it into the next state. */
    sealed interface Event<out T> {
        /** A load starts. */
        data object Load : Event<Nothing>

        /** A load answered [items]. */
        data class Loaded<T>(val items: List<T>) : Event<T>

        /** A load failed with [message]. */
        data class Failed(val message: String) : Event<Nothing>
    }

    companion object {
        /** The state after [event]: the only way a list screen's state changes. */
        fun <T> reduce(state: ListScreenState<T>, event: Event<T>): ListScreenState<T> = when (event) {
            Event.Load -> if (state.items.isEmpty()) Loading else Content(state.items)
            is Event.Loaded -> if (event.items.isEmpty()) Empty else Content(event.items)
            is Event.Failed -> Error(event.message, state.items)
        }
    }
}
