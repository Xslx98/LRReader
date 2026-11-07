package com.lanraragi.reader.archive

import com.lanraragi.reader.domain.splitNamespace

/** The rules for turning a plugin's proposal into a server tag string. */
object PluginTagMerge {

    private const val RATING_NAMESPACE = "rating"

    /**
     * A plugin result's `new_tags` (`" a, b"`) as a clean list: trimmed, no
     * blanks, the first spelling of case-only duplicates.
     */
    fun parseNewTags(raw: String?): List<String> = tokens(raw.orEmpty()).distinctBy { it.lowercase() }

    /**
     * Which of [selected] [serverTags] does not hold yet. Case-insensitive,
     * like the server's own check. A rating on the server stays as it is: it
     * is the user's own, and a second `rating:` tag would make it ambiguous.
     */
    fun additions(serverTags: String, selected: Collection<String>): List<String> {
        val server = tokens(serverTags)
        val hasRating = server.any(::isRating)
        val seen = server.mapTo(HashSet()) { it.lowercase() }
        return selected.map { it.trim() }.filter { tag ->
            tag.isNotEmpty() && !(hasRating && isRating(tag)) && seen.add(tag.lowercase())
        }
    }

    /** [serverTags] with [additions] on the end. Existing tags keep their spelling and order. */
    fun union(serverTags: String, additions: List<String>): String =
        (tokens(serverTags) + additions).joinToString(", ")

    private fun tokens(tags: String): List<String> =
        tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun isRating(tag: String): Boolean {
        val parts = splitNamespace(tag)
        return parts.size == 2 && parts[0].equals(RATING_NAMESPACE, ignoreCase = true)
    }
}
