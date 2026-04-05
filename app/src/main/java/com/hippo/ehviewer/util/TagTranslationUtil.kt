package com.hippo.ehviewer.util

import com.hippo.ehviewer.client.EhTagDatabase

object TagTranslationUtil {

    @JvmStatic
    fun getTagCN(tags: Array<String>, ehTags: EhTagDatabase?): String {
        if (ehTags != null && tags.size == 2) {
            var namespace = EhTagDatabase.prefixToNamespace("${tags[0]}:")
            if (namespace == null) {
                namespace = tags[0]
            }
            val group = ehTags.getTranslation("n:$namespace")
            //翻译标签名
            var prefix = EhTagDatabase.namespaceToPrefix(tags[0])
            if (tags[0].length == 1 && tags[0].matches("^[a-z]+$".toRegex())) {
                prefix = "${tags[0]}:"
            }

            val tagStr = ehTags.getTranslation(if (prefix != null) "$prefix${tags[1]}" else tags[1])

            return when {
                group != null && tagStr != null -> "$group:$tagStr"
                group != null && tagStr == null -> "$group:${tags[1]}"
                group == null && tagStr != null -> "${tags[0]}:$tagStr"
                else -> {
                    if (tagStr != null) "${tags[0]}:$tagStr"
                    else "${tags[0]}:${tags[1]}"
                }
            }
        } else {
            val s = StringBuilder()
            for (i in tags.indices) {
                if (i == 0) {
                    s.append(tags[i])
                } else {
                    s.append(":").append(tags[i])
                }
            }
            return s.toString()
        }
    }

    @JvmStatic
    fun getTagCNBody(tags: Array<String>, ehTags: EhTagDatabase?): String {
        if (ehTags != null && tags.size == 2) {
            val group = ehTags.getTranslation("n:${tags[0]}")
            //翻译标签名
            val prefix = EhTagDatabase.namespaceToPrefix(tags[0])
            val tagstr = ehTags.getTranslation(if (prefix != null) "$prefix${tags[1]}" else tags[1])

            return when {
                group != null && tagstr != null -> tagstr
                group != null && tagstr == null -> tags[1]
                group == null && tagstr != null -> tagstr
                else -> tags[1]
            }
        } else {
            val s = StringBuilder()
            for (i in tags) {
                s.setLength(0)
                s.append(i)
            }
            return s.toString()
        }
    }

    @JvmStatic
    fun getTagCN(tag: String?, ehTags: EhTagDatabase?): String {
        return getTagCN((tag ?: "").split(":").toTypedArray(), ehTags)
    }
}
