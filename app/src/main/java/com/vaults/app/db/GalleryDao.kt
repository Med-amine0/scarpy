package com.vaults.app.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GalleryDao {
    @Query("SELECT * FROM galleries WHERE parentId IS NULL ORDER BY sortOrder")
    fun getRootGalleries(): Flow<List<Gallery>>

    @Query("SELECT * FROM galleries WHERE parentId IS NULL ORDER BY sortOrder")
    suspend fun getRootGalleriesOnce(): List<Gallery>

    @Query("SELECT * FROM galleries WHERE parentId = :parentId ORDER BY sortOrder")
    fun getChildGalleries(parentId: Long): Flow<List<Gallery>>

    @Query("SELECT * FROM galleries WHERE parentId = :parentId ORDER BY sortOrder")
    suspend fun getChildGalleriesOnce(parentId: Long): List<Gallery>

    @Query("SELECT * FROM galleries WHERE id = :id")
    suspend fun getGalleryById(id: Long): Gallery?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(gallery: Gallery): Long

    @Update
    suspend fun update(gallery: Gallery)

    @Delete
    suspend fun delete(gallery: Gallery)

    @Query("DELETE FROM galleries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE galleries SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Query("UPDATE galleries SET columnCount = :columnCount WHERE id = :id")
    suspend fun updateColumnCount(id: Long, columnCount: Int)
}

@Dao
interface GalleryItemDao {
    @Query("SELECT * FROM gallery_items WHERE galleryId = :galleryId ORDER BY sortOrder")
    fun getItemsByGallery(galleryId: Long): Flow<List<GalleryItem>>

    @Query("SELECT * FROM gallery_items WHERE galleryId = :galleryId ORDER BY sortOrder")
    suspend fun getItemsOnce(galleryId: Long): List<GalleryItem>

    @Query("SELECT * FROM gallery_items WHERE id = :id")
    suspend fun getItemById(id: Long): GalleryItem?

    @Query("UPDATE gallery_items SET resolvedUrl = :url WHERE id = :id")
    suspend fun updateResolvedUrl(id: Long, url: String)

    @Query("SELECT value FROM gallery_items WHERE galleryId = :galleryId")
    suspend fun getExistingValues(galleryId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: GalleryItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<GalleryItem>)

    @Update
    suspend fun update(item: GalleryItem)

    @Delete
    suspend fun delete(item: GalleryItem)

    @Query("DELETE FROM gallery_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM gallery_items WHERE galleryId = :galleryId")
    suspend fun deleteByGalleryId(galleryId: Long)

    @Query("UPDATE gallery_items SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Query("UPDATE gallery_items SET weight = :weight WHERE id = :id")
    suspend fun updateWeight(id: Long, weight: Int)

    @Query("UPDATE gallery_items SET sortOrder = sortOrder + :shift WHERE galleryId = :galleryId")
    suspend fun shiftAllSortOrders(galleryId: Long, shift: Int)
}