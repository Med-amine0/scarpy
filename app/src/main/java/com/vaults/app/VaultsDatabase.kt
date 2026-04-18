package com.vaults.app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vaults.app.db.Gallery
import com.vaults.app.db.GalleryDao
import com.vaults.app.db.GalleryItem
import com.vaults.app.db.GalleryItemDao

@Database(
    entities = [Gallery::class, GalleryItem::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class VaultsDatabase : RoomDatabase() {
    abstract fun galleryDao(): GalleryDao
    abstract fun galleryItemDao(): GalleryItemDao

    companion object {
        @Volatile
        private var INSTANCE: VaultsDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE gallery_items ADD COLUMN resolvedUrl TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE gallery_items ADD COLUMN weight INTEGER NOT NULL DEFAULT 1")
            }
        }

        fun getInstance(context: Context): VaultsDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VaultsDatabase::class.java,
                    "vaults_db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
                INSTANCE = instance
                instance
            }
        }
    }
}