package com.lanraragi.reader.util

import com.lanraragi.reader.domain.splitNamespace
import com.lanraragi.reader.client.TagTranslationDatabase

/**
 * Display formatting for `namespace:value` tags on top of
 * [TagTranslationDatabase.translateTag] / [TagTranslationDatabase.translateNamespace].
 * Every function falls back to the original text, half by half, when the
 * dataset does not know a part or the database has not loaded yet.
 */
object TagTranslationUtil {

    /** `namespace:value` with both halves translated where the dataset knows them. */
    @JvmStatic
    fun getTagCN(tags: Array<String>, ehTags: TagTranslationDatabase?): String {
        if (ehTags == null || tags.size != 2) return tags.joinToString(":")
        val namespace = ehTags.translateNamespace(tags[0]) ?: tags[0]
        val value = ehTags.translateTag(tags[0], tags[1]) ?: tags[1]
        return "$namespace:$value"
    }

    /** The value half only; a bare tag (no namespace) is probed across the dataset namespaces. */
    @JvmStatic
    fun getTagCNBody(tags: Array<String>, ehTags: TagTranslationDatabase?): String {
        return when (tags.size) {
            2 -> translateValue(tags[0], tags[1], ehTags)
            1 -> translateValue(null, tags[0], ehTags)
            else -> tags.lastOrNull().orEmpty()
        }
    }

    /** One tag value under [namespace]; the original [value] when there is no translation. */
    @JvmStatic
    fun translateValue(namespace: String?, value: String, ehTags: TagTranslationDatabase?): String {
        return ehTags?.translateTag(namespace, value) ?: value
    }

    @JvmStatic
    fun getTagCN(tag: String?, ehTags: TagTranslationDatabase?): String {
        return getTagCN(splitNamespace(tag ?: ""), ehTags)
    }
}
