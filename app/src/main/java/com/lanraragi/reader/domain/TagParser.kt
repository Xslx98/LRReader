package com.lanraragi.reader.domain

/**
 * Tag parsing utilities for LANraragi tag formats.
 *
 * LRR tags are conceptually `namespace:value`. They appear in two
 * physical shapes across the wire/persistence boundary:
 *
 * - Comma-separated string from the API:  `"artist:foo, parody:bar, baz"`
 * - Flat array from Room `@Ignore` caches: `["artist:foo", "parody:bar", "baz"]`
 *
 * This file owns the namespace-split logic so both shapes share one
 * implementation; tags lacking a namespace fall under [DEFAULT_NAMESPACE].
 *
 * The grouped form ([com.lanraragi.reader.domain.TagGroup]) is built
 * directly from the parser's output and never re-parsed.
 */

private const val DEFAULT_NAMESPACE = "misc"

/**
 * The namespace bare (un-namespaced) tags are grouped under for display.
 * It is a display bucket, not a real namespace: see [toLrrTagString].
 */
const val BARE_TAG_BUCKET = DEFAULT_NAMESPACE

/**
 * Parse an LRR comma-separated tag string into a namespace map preserving
 * insertion order. Empty input returns an empty map.
 */
fun parseLrrTagString(tags: String): Map<String, List<String>> {
    if (tags.isEmpty()) return emptyMap()
    return groupTags(tags.splitToSequence(','))
}

/**
 * Group a flat array of `namespace:value` strings (or bare values) into a
 * namespace map preserving insertion order. `null` or empty returns an
 * empty map.
 */
fun groupFlatTags(simpleTags: Array<String>?): Map<String, List<String>> {
    if (simpleTags.isNullOrEmpty()) return emptyMap()
    return groupTags(simpleTags.asSequence())
}

/**
 * Strip the namespace prefix from a `namespace:value` tag, returning the
 * value alone. Bare tags (no colon) are returned trimmed.
 */
fun stripNamespace(tag: String): String {
    val trimmed = tag.trim()
    val colonIdx = trimmed.indexOf(':')
    return if (colonIdx > 0) trimmed.substring(colonIdx + 1).trim() else trimmed
}

private fun groupTags(rawTags: Sequence<String>): Map<String, List<String>> {
    val map = LinkedHashMap<String, MutableList<String>>()
    for (raw in rawTags) {
        val tag = raw.trim()
        if (tag.isEmpty()) continue
        val colonIdx = tag.indexOf(':')
        val namespace: String
        val value: String
        if (colonIdx > 0) {
            namespace = tag.substring(0, colonIdx).trim()
            value = tag.substring(colonIdx + 1).trim()
        } else {
            namespace = DEFAULT_NAMESPACE
            value = tag
        }
        map.getOrPut(namespace) { mutableListOf() }.add(value)
    }
    return map
}

/**
 * Values [raw] writes with an explicit `namespace:` prefix, e.g. the
 * `misc:x` tags a server really stores (as opposed to bare `x`).
 */
fun explicitlyNamespacedValues(raw: String, namespace: String): Set<String> {
    val out = HashSet<String>()
    for (part in raw.splitToSequence(',')) {
        val tag = part.trim()
        val colonIdx = tag.indexOf(':')
        if (colonIdx > 0 && tag.substring(0, colonIdx).trim() == namespace) {
            out.add(tag.substring(colonIdx + 1).trim())
        }
    }
    return out
}

/**
 * Serialize grouped tags back to LANraragi's comma-separated form. Tags in
 * the [BARE_TAG_BUCKET] display bucket are written bare, unless
 * [explicitBucketValues] says the server stored them as `misc:value`:
 * prefixing every bare tag rewrote `english` to `misc:english`.
 */
fun toLrrTagString(groups: List<TagGroup>, explicitBucketValues: Set<String>): String =
    buildList {
        for (group in groups) {
            for (tag in group.tags) {
                if (tag.isBlank()) continue
                val bare = group.namespace == BARE_TAG_BUCKET && tag !in explicitBucketValues
                add(if (bare) tag else "${group.namespace}:$tag")
            }
        }
    }.joinToString(", ")
