package com.lanraragi.reader.ui.list

import com.lanraragi.reader.ui.list.ListScreenState.Event
import org.junit.Assert.assertEquals
import org.junit.Test

/** A list screen's state changes only through [ListScreenState.reduce]. */
class ListScreenStateTest {

    private fun <T> ListScreenState<T>.on(event: Event<T>) = ListScreenState.reduce(this, event)

    @Test
    fun `a first load shows progress, then the items or the empty message`() {
        val loading = ListScreenState.Loading.on<String>(Event.Load)
        assertEquals(ListScreenState.Loading, loading)

        assertEquals(ListScreenState.Content(listOf("a", "b")), loading.on(Event.Loaded(listOf("a", "b"))))
        assertEquals(ListScreenState.Empty, loading.on(Event.Loaded(emptyList())))
    }

    @Test
    fun `a reload keeps showing the items instead of a spinner`() {
        val shown = ListScreenState.Content(listOf("a"))

        assertEquals(shown, shown.on(Event.Load))
    }

    @Test
    fun `a failure with nothing shown is the error`() {
        val state = ListScreenState.Loading.on<String>(Event.Failed("Server unreachable"))

        assertEquals(ListScreenState.Error("Server unreachable", emptyList<String>()), state)
        assertEquals(emptyList<String>(), state.items)
    }

    @Test
    fun `a failed reload keeps the items it had`() {
        val state = ListScreenState.Content(listOf("a", "b")).on(Event.Failed("timeout"))

        assertEquals(ListScreenState.Error("timeout", listOf("a", "b")), state)
        assertEquals("the screen still shows them", listOf("a", "b"), state.items)
    }

    @Test
    fun `retrying after an error loads again, keeping retained items`() {
        assertEquals(ListScreenState.Loading, ListScreenState.Error<String>("x", emptyList()).on(Event.Load))
        assertEquals(
            ListScreenState.Content(listOf("a")),
            ListScreenState.Error("x", listOf("a")).on(Event.Load),
        )
    }

    @Test
    fun `an empty answer after items is empty, a later answer shows the items`() {
        val empty = ListScreenState.Content(listOf("a")).on(Event.Loaded(emptyList()))
        assertEquals(ListScreenState.Empty, empty)
        assertEquals(ListScreenState.Content(listOf("b")), empty.on(Event.Loaded(listOf("b"))))
    }
}
