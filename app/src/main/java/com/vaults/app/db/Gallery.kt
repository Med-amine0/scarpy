package com.vaults.app.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "galleries",
    foreignKeys = [
        ForeignKey(
            entity = Gallery::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("parentId")]
)
data class Gallery(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val parentId: Long? = null,
    val name: String,
    val type: GalleryType = GalleryType.NORMAL,
    val loadMode: LoadMode = LoadMode.LAZY,
    val viewMode: ViewMode = ViewMode.GRID,
    val columnCount: Int = 3,
    val sortOrder: Int = 0
)

enum class GalleryType {
    NORMAL, CLIPS, REDGIF, FOLDER
}

enum class LoadMode {
    LAZY, ALL
}

enum class ViewMode {
    GRID, SWIPE
}