package com.lanraragi.reader.ui.list

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The one path that changes what a list adapter shows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ListDiffBinderTest {

    private data class Row(val id: Int, val label: String)

    private class Diff(private val old: List<Row>, private val new: List<Row>) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(o: Int, n: Int) = old[o].id == new[n].id
        override fun areContentsTheSame(o: Int, n: Int) = old[o] == new[n]

        // Asked during dispatch, after the binder took the new list.
        override fun getChangePayload(o: Int, n: Int) = "${old[o].label}->${new[n].label}"
    }

    /** An adapter over the binder's items that records every change it is told about. */
    private class Adapter(private val binder: ListDiffBinder<Row>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        val changes = mutableListOf<String>()

        init {
            registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeInserted(start: Int, count: Int) {
                    changes += "insert $start+$count"
                }

                override fun onItemRangeRemoved(start: Int, count: Int) {
                    changes += "remove $start+$count"
                }

                override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) {
                    changes += "change $start+$count $payload"
                }

                override fun onItemRangeMoved(from: Int, to: Int, count: Int) {
                    changes += "move $from->$to"
                }
            })
        }

        override fun getItemCount() = binder.items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(View(parent.context)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    private val a = Row(1, "a")
    private val b = Row(2, "b")
    private val c = Row(3, "c")

    private fun bound(): Pair<ListDiffBinder<Row>, Adapter> {
        val binder = ListDiffBinder<Row> { old, new -> Diff(old, new) }
        val adapter = Adapter(binder)
        binder.adapter = adapter
        return binder to adapter
    }

    @Test
    fun `a new list is dispatched as the difference to what the adapter showed`() {
        val (binder, adapter) = bound()
        binder.submit(listOf(a, b))
        adapter.changes.clear()

        binder.submit(listOf(a, b.copy(label = "B"), c))

        assertEquals(listOf(a, b.copy(label = "B"), c), binder.items)
        assertEquals("the payload compares the old item", listOf("insert 2+1", "change 1+1 b->B"), adapter.changes)
    }

    @Test
    fun `a drag moves the shown item, so the same order coming back changes nothing`() {
        val (binder, adapter) = bound()
        binder.submit(listOf(a, b, c))
        adapter.changes.clear()

        binder.move(0, 1)
        assertEquals(listOf(b, a, c), binder.items)
        assertEquals(listOf("move 0->1"), adapter.changes)

        adapter.changes.clear()
        binder.submit(listOf(b, a, c))
        assertEquals("no bounce-back", emptyList<String>(), adapter.changes)
    }

    @Test
    fun `the submitted list is copied, not shared`() {
        val (binder, _) = bound()
        val source = mutableListOf(a, b)
        binder.submit(source)
        source.clear()

        assertEquals(listOf(a, b), binder.items)
    }

    /** A view animating out keeps binding the items; a new view shows them from the start. */
    @Test
    fun `a new view continues from what is shown`() {
        val (binder, _) = bound()
        binder.submit(listOf(a, b))
        binder.adapter = null
        assertEquals("the old view still binds them", listOf(a, b), binder.items)

        val adapter = Adapter(binder)
        binder.adapter = adapter
        binder.submit(listOf(a, b, c))

        assertEquals(listOf("insert 2+1"), adapter.changes)
    }

    @Test
    fun `a move out of range is ignored`() {
        val (binder, adapter) = bound()
        binder.submit(listOf(a))
        adapter.changes.clear()

        binder.move(0, 3)

        assertEquals(listOf(a), binder.items)
        assertEquals(emptyList<String>(), adapter.changes)
    }
}
