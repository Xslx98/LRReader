package com.lanraragi.reader.ui.list

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A list screen's multi-select is one value, keyed by row, and survives the screen being re-created. */
class SelectionModelTest {

    @Test
    fun `picking a row enters the mode with it, and unpicking the last one leaves it`() {
        val model = SelectionModel()

        model.toggle("a")
        assertEquals(SelectionState.Selecting(setOf("a")), model.state)

        model.toggle("b")
        model.toggle("a")
        assertEquals(setOf("b"), model.checked)

        model.toggle("b")
        assertEquals(SelectionState.None, model.state)
        assertFalse(model.isActive)
    }

    @Test
    fun `entering with nothing picked stays on until a picked row is dropped`() {
        val saved = SavedStateHandle()
        val model = SelectionModel(saved)

        model.enter()
        assertEquals(SelectionState.Selecting(emptySet()), model.state)
        assertEquals(SelectionState.Selecting(emptySet()), SelectionModel(saved).state)
        model.retainAll(listOf("a"))
        assertTrue(model.isActive)

        model.toggle("a")
        model.toggle("a")
        assertEquals(SelectionState.None, model.state)
        assertEquals(SelectionState.None, SelectionModel(saved).state)
    }

    @Test
    fun `select all adds every given row while selecting, and nothing otherwise`() {
        val model = SelectionModel()
        model.selectAll(listOf("a", "b"))
        assertEquals(SelectionState.None, model.state)

        model.toggle("c")
        model.selectAll(listOf("a", "b", "c"))
        assertEquals(listOf("c", "a", "b"), model.checked.toList())
    }

    @Test
    fun `a list that no longer holds a row drops its check, and an emptied selection leaves the mode`() {
        val model = SelectionModel()
        model.selectAll(listOf("x"))
        model.toggle("a")
        model.toggle("b")

        model.retainAll(listOf("b", "c"))
        assertEquals(setOf("b"), model.checked)

        model.retainAll(listOf("c"))
        assertEquals(SelectionState.None, model.state)
    }

    @Test
    fun `listeners hear each change once, and a no-op is not a change`() {
        val model = SelectionModel()
        val heard = mutableListOf<SelectionState>()
        model.onChanged = { heard += it }

        model.setSelected("a", true)
        model.setSelected("a", true)
        model.retainAll(listOf("a"))
        model.exit()
        model.exit()

        assertEquals(listOf(SelectionState.Selecting(setOf("a")), SelectionState.None), heard)
    }

    @Test
    fun `a model re-created from the saved state selects the same rows`() {
        val saved = SavedStateHandle()
        SelectionModel(saved).apply {
            toggle("a")
            toggle("b")
        }

        val restored = SelectionModel(saved)
        assertTrue(restored.isActive)
        assertEquals(listOf("a", "b"), restored.checked.toList())

        restored.exit()
        assertEquals(SelectionState.None, SelectionModel(saved).state)
    }

    @Test
    fun `every change is saved, and a selection too large for the bundle is not`() {
        val saved = SavedStateHandle()
        val model = SelectionModel(saved)
        model.toggle("a")
        model.selectAll(listOf("b", "c"))
        model.retainAll(listOf("a", "c"))
        assertEquals(listOf("a", "c"), SelectionModel(saved).checked.toList())

        model.selectAll(List(SelectionModel.MAX_SAVED) { "k$it" })
        assertTrue(model.count > SelectionModel.MAX_SAVED)
        assertEquals(SelectionState.None, SelectionModel(saved).state)
    }
}
