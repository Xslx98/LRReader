package com.lanraragi.reader.widget

/**
 * One slot for a [ContentLayout.ContentHelper]'s rows between a view-state
 * save and the restore that follows it. The host keeps it on something that
 * outlives the view, a ViewModel. Main thread only.
 */
class SavedRows<E> {
    private var rows: ArrayList<E>? = null

    fun keep(rows: ArrayList<E>) {
        this.rows = rows
    }

    /** The kept rows, once: the slot is empty afterwards. */
    fun take(): ArrayList<E>? = rows.also { rows = null }
}
