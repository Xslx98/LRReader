package com.lanraragi.reader.widget

import kotlin.math.max
import kotlin.math.min

/** Page arithmetic behind [PaginationIndicator]. Pages are 1-based. */
internal object PageWindow {

    /** Number of pages [total] items fill at [pageSize] per page; 0 when there are no items. */
    fun pageCount(total: Int, pageSize: Int): Int {
        require(pageSize > 0) { "pageSize must be positive: $pageSize" }
        return if (total <= 0) 0 else (total - 1) / pageSize + 1
    }

    /** [page] moved into the valid range; page 1 when there are no pages. */
    fun clampPage(page: Int, pageCount: Int): Int = page.coerceIn(1, max(pageCount, 1))

    /**
     * The [maxTips] consecutive page numbers to show around [page]: centred on
     * it where possible, sliding at either end, fewer when there are fewer pages.
     */
    fun visiblePages(page: Int, pageCount: Int, maxTips: Int): IntRange {
        val size = min(maxTips, pageCount)
        if (size <= 0) return IntRange.EMPTY
        val start = (page - size / 2).coerceIn(1, pageCount - size + 1)
        return start until start + size
    }
}
