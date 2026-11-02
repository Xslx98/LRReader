package com.lanraragi.reader.domain

/**
 * Detail-page favorite indicator. Lives on
 * `GalleryDetailViewModel.favoriteState` as `null` (unresolved /
 * unknown), `FavoriteState(false, null)` (resolved → not favorited),
 * or `FavoriteState(true, displayName)` (resolved → favorited under
 * `displayName`).
 *
 * The display name composes the matched LRR category(ies) — see
 * `requestGalleryDetail` for the formatting.
 */
data class FavoriteState(
    val isFavorited: Boolean,
    val name: String?,
)
