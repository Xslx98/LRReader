package com.hippo.ehviewer.gallery

/**
 * Process-level holder for the most recent "opened from a list" reading
 * session (precedent: `com.hippo.ehviewer.download.DownloadResumeBanner`).
 * Not persisted: after process death the end-of-book panel simply offers no
 * "next" — graceful degradation by design.
 */
object ReadingContextStore {

    /** Max entries kept in a LocalList forward snapshot (anchor included). */
    const val LOCAL_WINDOW = 50

    @Volatile
    private var context: ReadingContext? = null

    /** Called by list UIs when the user opens an item. Last publisher wins. */
    fun publish(ctx: ReadingContext) {
        context = ctx
    }

    /**
     * The context anchored on [arcid], or null. The anchor check rejects
     * stale contexts (e.g. reader opened via deep link while the store still
     * holds an unrelated list's context).
     */
    fun currentFor(arcid: String): ReadingContext? =
        context?.takeIf { it.anchorArcid == arcid }

    /** Move the anchor forward after a continuation jump. */
    fun advance(ctx: ReadingContext) {
        context = ctx
    }

    fun clear() {
        context = null
    }
}
