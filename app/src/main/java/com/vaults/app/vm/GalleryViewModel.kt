package com.vaults.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.vaults.app.VaultsApp
import com.vaults.app.db.Gallery
import com.vaults.app.db.GalleryItem
import com.vaults.app.db.GalleryType
import com.vaults.app.db.LoadMode
import com.vaults.app.db.ViewMode
import com.vaults.app.scraper.PHScraper
import com.vaults.app.scraper.RedGifScraper
import com.vaults.app.scraper.ThumbnailDownloader
import com.vaults.app.scraper.InputParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = VaultsApp.instance.db.galleryDao()
    private val itemDao = VaultsApp.instance.db.galleryItemDao()

    private val _rootGalleries = MutableLiveData<List<Gallery>>()
    val rootGalleries: LiveData<List<Gallery>> = _rootGalleries

    private val _currentGallery = MutableLiveData<Gallery?>()
    val currentGallery: LiveData<Gallery?> = _currentGallery

    private val _resolvedItems = MutableStateFlow<List<ResolvedItem>>(emptyList())
    val resolvedItems: StateFlow<List<ResolvedItem>> = _resolvedItems

    private val _editMode = MutableLiveData(false)
    val editMode: LiveData<Boolean> = _editMode

    private var currentGalleryType: GalleryType = GalleryType.NORMAL
    private var currentLoadMode: LoadMode = LoadMode.LAZY

    init {
        viewModelScope.launch {
            dao.getRootGalleries().collect { list ->
                _rootGalleries.postValue(list)
            }
        }
    }

    fun loadChildGalleries(parentId: Long): LiveData<List<Gallery>> = MutableLiveData<List<Gallery>>().also { liveData ->
        viewModelScope.launch {
            dao.getChildGalleries(parentId).collect { list ->
                liveData.postValue(list)
            }
        }
    }

    suspend fun getGalleryById(id: Long): Gallery? = withContext(Dispatchers.IO) {
        dao.getGalleryById(id)
    }

    fun setCurrentGallery(gallery: Gallery) {
        _currentGallery.value = gallery
        currentGalleryType = gallery.type
        currentLoadMode = gallery.loadMode
    }

    fun loadGallery(galleryId: Long) {
        viewModelScope.launch {
            val items = itemDao.getItemsOnce(galleryId)
            val resolved = items.map { ResolvedItem(it) }
            _resolvedItems.value = resolved

            if (currentLoadMode == LoadMode.ALL) {
                resolveAllItems(galleryId)
            }
        }
    }

    private suspend fun resolveAllItems(galleryId: Long) {
        val items = _resolvedItems.value.toMutableList()
        
        items.forEachIndexed { index, item ->
            if (!item.isLoading && item.resolvedUrl == null) {
                items[index] = item.copy(isLoading = true)
                _resolvedItems.value = items.toList()
                
                resolveSingleItem(items[index], galleryId)
            }
        }
    }

    fun resolveItem(item: ResolvedItem) {
        viewModelScope.launch {
            resolveSingleItem(item, item.galleryId)
        }
    }

    private suspend fun resolveSingleItem(item: ResolvedItem, galleryId: Long) {
        val items = _resolvedItems.value.toMutableList()
        val index = items.indexOfFirst { it.id == item.id }
        if (index == -1) return

        items[index] = item.copy(isLoading = true)
        _resolvedItems.value = items.toList()

        when (currentGalleryType) {
            GalleryType.PORNHUB -> {
                val phResult = PHScraper.getFreshUrl(item.value)
                items[index] = items[index].copy(
                    resolvedUrl = phResult?.bestUrl(),
                    isLoading = false,
                    error = if (phResult == null) "Failed to load" else null
                )
                _resolvedItems.value = items.toList()
                return
            }
            GalleryType.REDGIF -> {
                val rgResult = RedGifScraper.getDirectUrl(item.value)
                items[index] = items[index].copy(
                    resolvedUrl = rgResult.bestUrl(),
                    embedUrl = rgResult.embedUrl,
                    isLoading = false,
                    error = null
                )
                _resolvedItems.value = items.toList()
                return
            }
            else -> {
                items[index] = items[index].copy(
                    resolvedUrl = item.value,
                    isLoading = false
                )
                _resolvedItems.value = items.toList()
            }
        }
    }

    suspend fun createGallery(name: String, type: GalleryType, parentId: Long? = null): Long = withContext(Dispatchers.IO) {
        val gallery = Gallery(
            name = name,
            type = type,
            parentId = parentId,
            sortOrder = 0
        )
        dao.insert(gallery)
    }

    fun addItems(galleryId: Long, input: String) {
        viewModelScope.launch {
            val values = InputParser.parse(input)
            val existing = itemDao.getExistingValues(galleryId).toSet()
            val newValues = values.filter { it !in existing }
            
            val currentMax = itemDao.getItemsOnce(galleryId).maxOfOrNull { it.sortOrder } ?: -1
            
            val items = newValues.mapIndexed { index, value ->
                GalleryItem(
                    galleryId = galleryId,
                    value = value,
                    sortOrder = currentMax + index + 1
                )
            }
            
            itemDao.insertAll(items)
            
            if (currentGalleryType != GalleryType.REDGIF) {
                items.forEach { item ->
                    val thumbPath = ThumbnailDownloader.downloadThumbnail(item.id, currentGalleryType, item.value)
                    if (thumbPath != null) {
                        itemDao.updateThumbnailPath(item.id, thumbPath)
                    }
                }
            }
            
            loadGallery(galleryId)
        }
    }

    suspend fun deleteItem(itemId: Long) = withContext(Dispatchers.IO) {
        ThumbnailDownloader.deleteThumbnail(itemId)
        itemDao.deleteById(itemId)
    }

    suspend fun updateGallery(gallery: Gallery) = withContext(Dispatchers.IO) {
        dao.update(gallery)
    }

    suspend fun updateGallerySettings(galleryId: Long, columnCount: Int, loadMode: LoadMode, viewMode: ViewMode) = withContext(Dispatchers.IO) {
        val gallery = dao.getGalleryById(galleryId) ?: return@withContext
        dao.update(gallery.copy(columnCount = columnCount, loadMode = loadMode, viewMode = viewMode))
    }

    suspend fun deleteGallery(galleryId: Long) = withContext(Dispatchers.IO) {
        val items = itemDao.getItemsOnce(galleryId)
        items.forEach { ThumbnailDownloader.deleteThumbnail(it.id) }
        itemDao.deleteByGalleryId(galleryId)
        dao.deleteById(galleryId)
    }

    fun toggleEditMode() {
        _editMode.value = !(_editMode.value ?: false)
    }

    suspend fun reorderGalleries(galleries: List<Gallery>) = withContext(Dispatchers.IO) {
        galleries.forEachIndexed { index, gallery ->
            dao.updateSortOrder(gallery.id, index)
        }
    }
}