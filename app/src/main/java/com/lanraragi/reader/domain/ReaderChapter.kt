package com.lanraragi.reader.domain

/**
 * One row of the reader's chapter list.
 *
 * @property title may be blank: the UI shows a fallback title
 * @property page0 the reader's 0-indexed page to jump to (global in a tank session)
 * @property depth 0 for an archive chapter or a tank member, 1 for a chapter inside a member
 */
data class ReaderChapter(
    val title: String,
    val page0: Int,
    val depth: Int = 0,
)

/**
 * What a reader session currently offers as its chapter list.
 *
 * @property alwaysOffered the entry point shows even with no [entries] yet (tank sessions)
 * @property loading more entries are on their way
 * @property failed the last load failed; [entries] holds what is known without it
 */
data class ReaderChapterList(
    val entries: List<ReaderChapter> = emptyList(),
    val alwaysOffered: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false,
) {
    val showEntryPoint: Boolean get() = alwaysOffered || entries.isNotEmpty()
}
