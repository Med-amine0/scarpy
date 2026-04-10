package com.vaults.app.vm

import com.vaults.app.db.GalleryItem

data class ResolvedItem(
    val id: Long,
    val galleryId: Long,
    val value: String,
    val thumbnailPath: String?,
    var resolvedUrl: String?,
    var embedUrl: String?,
    var isLoading: Boolean,
    var error: String?,
    val sortOrder: Int
) {
    constructor(item: GalleryItem) : this(
        id = item.id,
        galleryId = item.galleryId,
        value = item.value,
        thumbnailPath = item.thumbnailPath,
        resolvedUrl = null,
        embedUrl = null,
        isLoading = false,
        error = null,
        sortOrder = item.sortOrder
    )
}