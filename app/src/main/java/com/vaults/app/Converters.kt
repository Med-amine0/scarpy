package com.vaults.app

import androidx.room.TypeConverter
import com.vaults.app.db.GalleryType
import com.vaults.app.db.LoadMode
import com.vaults.app.db.ViewMode

class Converters {
    @TypeConverter
    fun fromGalleryType(value: GalleryType): String = value.name

    @TypeConverter
    fun toGalleryType(value: String): GalleryType = GalleryType.valueOf(value)

    @TypeConverter
    fun fromLoadMode(value: LoadMode): String = value.name

    @TypeConverter
    fun toLoadMode(value: String): LoadMode = LoadMode.valueOf(value)

    @TypeConverter
    fun fromViewMode(value: ViewMode): String = value.name

    @TypeConverter
    fun toViewMode(value: String): ViewMode = ViewMode.valueOf(value)
}