package com.lanraragi.reader.domain

import kotlinx.serialization.Serializable

/** A tankoubon as the server lists it: an ordered collection of archive ids with its own metadata and progress. */
@Serializable
data class Tankoubon(
    val id: String = "",
    val name: String = "",
    val archives: List<String> = emptyList(),
    val summary: String? = null,
    val tags: String? = null,
    val progress: Int = 0,
)
