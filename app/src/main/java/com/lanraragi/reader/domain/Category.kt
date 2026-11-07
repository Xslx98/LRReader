package com.lanraragi.reader.domain

/**
 * A LANraragi category as the code above transport sees it. Static categories
 * have an empty [search] and hand-managed [archives]; dynamic ones have a
 * non-empty [search] that the server matches.
 *
 * Every parameter is required so a mapper cannot silently leave one at a
 * default: an edit sends these values back to the server. Not a data class:
 * like the DTO it mirrors it compares by identity, so publishing a reloaded
 * list never deep-compares archive id lists (up to ~100k ids each).
 */
class Category(
    val id: String?,
    val name: String?,
    val archives: List<String>,
    val search: String?,
    private val pinned: Boolean,
) {
    /** @return true if this is a dynamic category (search-based). */
    fun isDynamic(): Boolean = !search.isNullOrEmpty()

    /** @return true if this category is pinned. */
    fun isPinned(): Boolean = pinned
}
