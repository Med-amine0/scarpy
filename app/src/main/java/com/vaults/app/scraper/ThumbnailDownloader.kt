package com.vaults.app.scraper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.vaults.app.VaultsApp
import com.vaults.app.db.GalleryType
import com.vaults.app.db.LoadMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object ThumbnailDownloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val thumbnailDir: File
        get() = File(VaultsApp.instance.filesDir, "thumbnails").also { it.mkdirs() }

    fun getThumbnailFile(itemId: Long): File = File(thumbnailDir, "thumb_$itemId.jpg")

    suspend fun downloadThumbnail(
        itemId: Long,
        galleryType: GalleryType,
        value: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val url = when (galleryType) {
                GalleryType.NORMAL -> value
                GalleryType.PORNHUB -> PHScraper.getThumbnail(value)
                GalleryType.REDGIF -> null
                GalleryType.FOLDER -> return@withContext null
            }

            url ?: return@withContext null

            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) return@withContext null
            
            val bytes = response.body?.bytes() ?: return@withContext null
            
            val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (original == null) return@withContext null

            val maxSize = 200
            val scale = minOf(
                maxSize.toFloat() / original.width,
                maxSize.toFloat() / original.height
            )
            
            val newWidth = (original.width * scale).toInt()
            val newHeight = (original.height * scale).toInt()
            
            val compressed = Bitmap.createScaledBitmap(original, newWidth, newHeight, true)
            original.recycle()
            
            if (compressed == null) return@withContext null
            
            val file = getThumbnailFile(itemId)
            FileOutputStream(file).use { out ->
                compressed.compress(Bitmap.CompressFormat.JPEG, 70, out)
            }
            compressed.recycle()
            
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun deleteThumbnail(itemId: Long) {
        val file = getThumbnailFile(itemId)
        if (file.exists()) file.delete()
    }

    suspend fun downloadAllForGallery(
        galleryId: Long,
        galleryType: GalleryType,
        loadMode: LoadMode,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (galleryType == GalleryType.FOLDER) return@withContext
        
        val items = VaultsApp.instance.db.galleryItemDao().getItemsOnce(galleryId)
        if (loadMode == LoadMode.LAZY) return@withContext
        
        items.forEachIndexed { index, item ->
            if (item.thumbnailPath == null && galleryType != GalleryType.REDGIF) {
                val path = downloadThumbnail(item.id, galleryType, item.value)
                if (path != null) {
                    VaultsApp.instance.db.galleryItemDao().updateThumbnailPath(item.id, path)
                }
            }
            onProgress(index + 1, items.size)
        }
    }
}