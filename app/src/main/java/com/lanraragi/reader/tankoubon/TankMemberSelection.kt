package com.lanraragi.reader.tankoubon

/**
 * Multi-select state for the member management list (spec 2026-09-21 §4).
 * Pure: holds the selected member ids (insertion-ordered) and whether the
 * mode is active; the scene renders it. Mirrors the list scenes' choice
 * mode: a long-press enters and checks, unchecking the last row leaves.
 */
class TankMemberSelection(private val onChanged: () -> Unit = {}) {

    private val ids = LinkedHashSet<String>()

    var isActive: Boolean = false
        private set

    val selected: Set<String> get() = ids

    val count: Int get() = ids.size

    fun isSelected(id: String): Boolean = id in ids

    /** Long-press entry: activates the mode (if needed) and toggles [id]. */
    fun enterAndToggle(id: String) {
        isActive = true
        toggle(id)
    }

    /**
     * Toggles [id] while active; the mode ends when nothing is left
     * selected. Returns false (not consumed) when the mode is inactive.
     */
    fun toggle(id: String): Boolean {
        if (!isActive) return false
        if (!ids.remove(id)) ids.add(id)
        if (ids.isEmpty()) isActive = false
        onChanged()
        return true
    }

    /** Selects every id in [all] (list order); a no-op when inactive. */
    fun selectAll(all: List<String>) {
        if (!isActive) return
        ids.clear()
        ids.addAll(all)
        onChanged()
    }

    /** Drops ids that are no longer members (after a reload / removal). */
    fun retainAll(members: Collection<String>) {
        if (!isActive) return
        val changed = ids.retainAll(members.toSet())
        if (ids.isEmpty()) isActive = false
        if (changed) onChanged()
    }

    fun clear() {
        if (!isActive && ids.isEmpty()) return
        ids.clear()
        isActive = false
        onChanged()
    }
}
