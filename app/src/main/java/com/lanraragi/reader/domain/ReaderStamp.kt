package com.lanraragi.reader.domain

import kotlinx.serialization.Serializable

/** A text annotation pinned to a normalized (0-100) position on one archive page. */
@Serializable
data class ReaderStamp(
    val id: String = "",
    /** Raw "x,y" percentage string (0-100, floats); may be malformed. */
    val position: String = "",
    val content: String = ""
)
