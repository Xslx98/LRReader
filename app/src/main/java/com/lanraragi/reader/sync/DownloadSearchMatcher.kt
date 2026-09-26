package com.lanraragi.reader.sync

import com.lanraragi.reader.dao.DownloadInfo
import com.lanraragi.reader.domain.splitNamespace

/**
 * Local search over the Downloads list. A download matches when the whole key
 * appears in its title, or when every comma-separated term matches one of its
 * tags: a `namespace:value` term matches that tag exactly, a bare term matches
 * any tag's value. Case is ignored throughout.
 */
object DownloadSearchMatcher {

    fun matches(info: DownloadInfo, key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return true
        if (info.title?.contains(trimmed, ignoreCase = true) == true) return true
        val tags = info.simpleTags ?: return false
        val terms = trimmed.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return terms.isNotEmpty() && terms.all { term -> tags.any { tag -> termMatches(term, tag) } }
    }

    private fun termMatches(term: String, tag: String): Boolean {
        val tagParts = splitNamespace(tag)
        val termParts = splitNamespace(term)
        return if (termParts.size == 2) {
            tagParts.size == 2 &&
                tagParts[0].equals(termParts[0], ignoreCase = true) &&
                tagParts[1].equals(termParts[1], ignoreCase = true)
        } else {
            tagParts.last().equals(term, ignoreCase = true)
        }
    }
}
