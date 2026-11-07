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
