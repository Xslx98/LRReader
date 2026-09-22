package com.lanraragi.reader.tankoubon

/**
 * Pure rules for the MATERIALIZED tank tag string (spec 2026-09-22 §5):
 * a tankoubon's own `tags` = the union of its members' tags, kept in sync
 * by the client on every membership change it performs.
 *
 * Vocabulary: a "tag" is one `ns:value` (or bare) entry of a LANraragi
 * comma-separated tag string. Comparison is case-insensitive on the whole
 * entry, the FIRST spelling seen is kept. Output order = existing tank
 * order, then new tags in member order.
 *
 * [excludedNamespaces] never enter the union (per-archive facts); tags
 * the tank already holds in those namespaces (its own rating above all)
 * are preserved verbatim by every rule.
 */
object TankTagMerge {

    val excludedNamespaces: Set<String> = setOf("date_added", "timestamp", "source", "rating")

    private const val SEPARATOR = ", "

    /** Trimmed, non-empty entries of a LANraragi tag string. */
    fun split(tags: String?): List<String> =
        tags.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun join(tags: List<String>): String = tags.joinToString(SEPARATOR)

    /** True when [tag] belongs to one of [excludedNamespaces] (namespace compared trimmed, case-insensitively). */
    fun isExcluded(tag: String): Boolean {
        val colon = tag.indexOf(':')
        if (colon <= 0) return false
        return tag.substring(0, colon).trim().lowercase() in excludedNamespaces
    }

    /**
     * Union of every member's tags in member order, minus the excluded
     * namespaces, deduped case-insensitively (first spelling kept).
     */
    fun union(memberTags: List<String?>): List<String> =
        dedupe(memberTags.flatMap { split(it) }.filterNot { isExcluded(it) })

    /** Create from selection / add members: `tank ∪ new members`. */
    fun onAdd(tankTags: String?, newMembersTags: List<String?>): String =
        join(dedupe(split(tankTags) + union(newMembersTags)))

    /**
     * Rule 3 for removals: `manual = tank − ⋃(members before)`,
     * `new = ⋃(remaining) ∪ manual`. Hand-written tags survive; tags only
     * the removed members contributed go; excluded-namespace tank tags are
     * part of `manual` by construction (the union never holds them).
     */
    fun onRemove(tankTags: String?, membersBefore: List<String?>, membersRemaining: List<String?>): String {
        val before = union(membersBefore).map { it.lowercase() }.toHashSet()
        val remaining = union(membersRemaining)
        val remainingSet = remaining.map { it.lowercase() }.toHashSet()
        // Tank order first: every tank tag that survives (manual, or still
        // contributed by a remaining member) keeps its position; tags the
        // remaining members add that the tank lacked follow in member order.
        val kept = split(tankTags).filter { val l = it.lowercase(); l !in before || l in remainingSet }
        return join(dedupe(kept + remaining))
    }

    /**
     * A member's own tags were edited: rule 3 with [membersBefore] holding
     * that member's OLD tags and [membersAfter] its NEW tags.
     */
    fun onMemberTagsChanged(tankTags: String?, membersBefore: List<String?>, membersAfter: List<String?>): String =
        onRemove(tankTags, membersBefore, membersAfter)

    /**
     * 「重置为成员并集」: drop every tank tag outside the excluded namespaces,
     * keep the excluded ones verbatim, recompute the union of the current
     * members.
     */
    fun reset(tankTags: String?, membersTags: List<String?>): String =
        join(dedupe(split(tankTags).filter { isExcluded(it) } + union(membersTags)))

    /**
     * Auto-fill gate (§4.5): a tank whose own tags hold nothing outside the
     * excluded namespaces, with at least one member, gets the union written
     * once on first detail open.
     */
    fun needsAutoFill(tankTags: String?, memberCount: Int): Boolean =
        memberCount > 0 && split(tankTags).none { !isExcluded(it) }

    private fun dedupe(tags: List<String>): List<String> {
        val seen = HashSet<String>()
        return tags.filter { seen.add(it.lowercase()) }
    }
}
