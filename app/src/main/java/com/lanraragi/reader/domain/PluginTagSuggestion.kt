package com.lanraragi.reader.domain

/** What one plugin run proposes. Nothing here has been written to the server. */
class PluginTagSuggestion(
    /** Tags the server says the archive lacks, trimmed, without case-only duplicates. */
    val newTags: List<String>,
    /** A replacement title, if the plugin found one and the server allows replacing titles. */
    val title: String?,
    val summary: String?,
)

/** The part of a [PluginTagSuggestion] the user confirmed. */
class PluginTagChanges(
    val tags: List<String> = emptyList(),
    val title: String? = null,
    val summary: String? = null,
) {
    val isEmpty: Boolean get() = tags.isEmpty() && title == null && summary == null
}
