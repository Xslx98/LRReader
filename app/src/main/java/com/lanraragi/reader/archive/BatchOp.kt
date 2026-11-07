package com.lanraragi.reader.archive

/** A batch operation applied to a gallery list's multi-selection of archives. */
sealed interface BatchOp {
    data object Download : BatchOp
    data class AddToCategory(val categoryId: String) : BatchOp
    /**
     * @param wasEmpty true when the tank had no members before this batch
     *   (as observed by the picker) — the batch then seeds the tank cover
     *   from global page 1 after the first successful add, because the
     *   server's append endpoint never generates one on its own.
     * @param tankName display name as the picker saw it (or as typed for a
     *   just-created tank); carried on the op so the list can build the
     *   provisional tank row and the Snackbar without another fetch.
     * @param knownMembers the tank's member ids as the picker saw them;
     *   selected archives among them are skipped (no append) and reported
     *   in the batch result's `alreadyMembers`.
     */
    data class AddToTankoubon(
        val tankId: String,
        val wasEmpty: Boolean = false,
        val tankName: String = "",
        val knownMembers: Set<String> = emptySet(),
    ) : BatchOp
    data object ClearNew : BatchOp
    data object DeleteArchives : BatchOp

    // Tank mode (rows are TANK_ entries): docs/plan-2.4.0-tank-ux.md PR-B.

    /** Queue every member of each tank for download as a tank group. */
    data object DownloadTanks : BatchOp

    /** Auto-sort each tank's members by title; previous orders land in the batch result's `tankUndoOrders`. */
    data object SortTanks : BatchOp

    /** Delete the tanks (never their archives). */
    data object DeleteTanks : BatchOp
}
