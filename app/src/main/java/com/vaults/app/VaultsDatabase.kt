package com.vaults.app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vaults.app.db.Gallery
import com.vaults.app.db.GalleryDao
import com.vaults.app.db.GalleryItem
import com.vaults.app.db.GalleryItemDao

@Database(
    entities = [Gallery::class, GalleryItem::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class VaultsDatabase : RoomDatabase() {
    abstract fun galleryDao(): GalleryDao
    abstract fun galleryItemDao(): GalleryItemDao

    companion object {
        @Volatile
        private var INSTANCE: VaultsDatabase? = null

        fun getInstance(context: Context): VaultsDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VaultsDatabase::class.java,
                    "vaults_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}