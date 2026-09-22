package com.lanraragi.reader.ui.scene.gallery.list

import com.lanraragi.reader.domain.splitNamespace
import com.lanraragi.reader.dao.QuickSearch

/**
 * Decides which quick-search names the bookmarks drawer rewrites when it
 * loads the list (EhViewer legacy: tag-shaped names are shown translated
 * when tag translations are on).
 *
 * The old code also did the reverse — with translations OFF it overwrote
 * every colon-free name with the entry's keyword and persisted that. Since
 * v1.21.0 a category quick search has no keyword (the category id carries
 * the filter), so that branch wiped such names to NULL on the next drawer
 * load, and it clobbered user-chosen names and the "Category · keyword"
 * defaults as well. That branch is gone: with translations off, names are
 * never touched.
 */
internal object QuickSearchNameReconciler {

    /**
     * Mutates `name` in place for the entries that need it and returns those
     * entries (the caller persists them). [translate] receives the two parts
     * of a `namespace:tag` name.
     */
    fun reconcile(
        list: List<QuickSearch>,
        showTranslations: Boolean,
        translate: (Array<String>) -> String
    ): List<QuickSearch> {
        if (!showTranslations) return emptyList()
        val changed = mutableListOf<QuickSearch>()
        for (qs in list) {
            val name = qs.name ?: continue
            val parts = splitNamespace(name)
            if (parts.size != 2) continue
            val translated = translate(parts)
            if (translated != name) {
                qs.name = translated
                changed.add(qs)
            }
        }
        return changed
    }
}
