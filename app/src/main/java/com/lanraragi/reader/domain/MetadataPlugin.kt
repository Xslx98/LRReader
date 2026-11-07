package com.lanraragi.reader.domain

/**
 * A metadata plugin installed on a server, as the chooser shows it. The
 * plugin's `parameters` (settings, possibly saved credentials) are deliberately
 * absent: the client neither needs nor keeps them.
 */
class MetadataPlugin(
    /** The id the server runs it by. */
    val namespace: String,
    val name: String,
    val version: String,
    /** May hold HTML: the server's own plugin pages render it. */
    val description: String,
    /** Label of the plugin's one optional free-text argument; null when it takes none. May hold HTML. */
    val oneshotLabel: String?,
    /** Namespace of the login plugin this one signs in with; null when it needs none. */
    val loginFrom: String?,
)
