package com.hippo.ehviewer.client.data.userTag

class TagPushParam {

    @JvmField var userTagAction: String? = null
    @JvmField var tagNameNew: String? = null
    @JvmField var tagWatchNew: String? = null
    @JvmField var tagHiddenNew: String? = null
    @JvmField var tagColorNew: String? = null
    @JvmField var tagWeightNew: Int = 0
    @JvmField var userTagTarget: Int = 0

    fun addTagParam(): String {
        var state = ""

        if (tagHiddenNew != null && tagHiddenNew == "on") {
            state = "&taghide_new=on"
        }
        if (tagWatchNew != null && tagWatchNew == "on") {
            state = "&tagwatch_new=on"
        }

        return "usertag_action=add&tagname_new=" + getEncodeTagName() +
            state + "&tagcolor_new=" + getEncodeColorName() +
            "&tagweight_new=10&usertag_target=0"
    }

    private fun getEncodeTagName(): String {
        val tagName = tagNameNew ?: return ""
        // Note: original Java code called replace() but discarded the result (String is immutable).
        // Preserving the same (no-op) behavior.
        tagName.replace(":", "%3A")
        tagName.replace(" ", "+")
        return tagName
    }

    private fun getEncodeColorName(): String {
        val tagColor = tagColorNew ?: return ""
        // Note: original Java code called replace() but discarded the result (String is immutable).
        // Preserving the same (no-op) behavior.
        tagColor.replace("#", "%23")
        return tagColor
    }
}
