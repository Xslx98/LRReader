package com.lanraragi.reader.ui.scene.download

/**
 * One requested jump to an archive on the Downloads list, which a cold start
 * or a label change only fills later. It ends on the first list that can
 * answer, so a row hidden by a search can't cause a jump long after the tap.
 */
internal class PendingArchiveJump {

    private var arcid: String? = null

    fun request(arcid: String) {
        this.arcid = arcid
    }

    /**
     * Tries the list as it stands, which may still be the one from before the
     * request: a miss proves nothing, so the request stays.
     *
     * @return the row's index, or null when there is nothing to jump to yet
     */
    fun resolveNow(indexOf: (String) -> Int): Int? {
        val index = indexOf(arcid ?: return null)
        if (index < 0) return null
        arcid = null
        return index
    }

    /**
     * Answers from a list published after the request. An empty list is one
     * still loading, so it leaves the request open; any other list ends it.
     *
     * @return the row's index, or null when the list doesn't have it
     */
    fun resolveOnNewList(isEmpty: Boolean, indexOf: (String) -> Int): Int? {
        if (isEmpty) return null
        val index = indexOf(arcid ?: return null)
        arcid = null
        return index.takeIf { it >= 0 }
    }
}
