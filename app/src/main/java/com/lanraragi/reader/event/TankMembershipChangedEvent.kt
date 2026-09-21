package com.lanraragi.reader.event

/**
 * Emitted after the detail page's Tankoubons › Edit dialog applied its
 * membership diff for [arcid] on the server. Gallery lists play the
 * merge-into-tankoubon choreography for the first joined tank (group mode)
 * or silently reload when the archive only left tanks.
 *
 * @param joined tanks the archive was appended to, in dialog order
 * @param left tank ids the archive was removed from
 */
data class TankMembershipChangedEvent(
    val arcid: String,
    val joined: List<JoinedTank>,
    val left: List<String>,
) {
    /** @param wasEmpty the tank had no members before this add (its cover gets seeded). */
    data class JoinedTank(val id: String, val name: String, val wasEmpty: Boolean)
}
