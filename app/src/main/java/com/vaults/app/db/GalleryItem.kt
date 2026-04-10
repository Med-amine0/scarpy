package com.vaults.app.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "gallery_items",
    foreignKeys = [
        ForeignKey(
            entity = Gallery::class,
            parentColumns = ["id"],
            childColumns = ["galleryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("galleryId")]
)
data class GalleryItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val galleryId: Long,
    val value: String,
    val thumbnailPath: String? = null,
    val sortOrder: Int = 0
)