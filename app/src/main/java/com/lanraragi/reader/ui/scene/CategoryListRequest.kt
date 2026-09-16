package com.lanraragi.reader.ui.scene

import com.lanraragi.reader.client.data.ListUrlBuilder
import com.lanraragi.reader.client.api.data.LRRCategory

/**
 * Turns a tapped category into the gallery-list request that opens it.
 *
 * Static and dynamic categories both open by id: the server resolves a
 * dynamic category's search string itself when it receives
 * `/api/search?category=<id>`, so the client never has to (and never should)
 * put that raw query — or the opaque id — into the keyword. Returns null
 * when the category carries no usable id (never expected from a real server;
 * defensive only). Pure so it stays unit-testable without inflating the Scene.
 */
internal fun categoryListBuilder(category: LRRCategory): ListUrlBuilder? {
    val id = category.id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return ListUrlBuilder().apply {
        mode = ListUrlBuilder.MODE_NORMAL
        categoryId = id
        categoryName = category.name
    }
}
