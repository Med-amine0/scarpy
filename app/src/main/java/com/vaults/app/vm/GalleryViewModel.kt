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
import com.vaults.app.scraper.InputParser
import com.vaults.app.scraper.MediaResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MediaItemState(
    val id: Long,
    val value: String,
    val sortOrder: Int,
    val url: String? = null,
    val embedUrl: String? = null,
    val isVideo: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null
) {
    companion object {
        fun fromGalleryItem(item: GalleryItem) = MediaItemState(
            id = item.id,
            value = item.value,
            sortOrder = item.sortOrder,
            isLoading = true
        )
    }
}

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = VaultsApp.instance.db.galleryDao()
    private val itemDao = VaultsApp.instance.db.galleryItemDao()

    private val _rootGalleries = MutableLiveData<List<Gallery>>()
    val rootGalleries: LiveData<List<Gallery>> = _rootGalleries

    private val _currentGallery = MutableLiveData<Gallery?>()
    val currentGallery: LiveData<Gallery?> = _currentGallery

    private val _mediaItems = MutableStateFlow<List<MediaItemState>>(emptyList())
    val mediaItems: StateFlow<List<MediaItemState>> = _mediaItems.asStateFlow()

    private var currentGalleryType: GalleryType = GalleryType.NORMAL

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
    }

    fun loadGallery(galleryId: Long) {
        viewModelScope.launch {
            val items = itemDao.getItemsOnce(galleryId)
            val states = items.map { MediaItemState.fromGalleryItem(it) }
            _mediaItems.value = states

            resolveAllItems(states, galleryId)
        }
    }

    private suspend fun resolveAllItems(items: List<MediaItemState>, galleryId: Long) {
        items.forEach { item ->
            if (item.isLoading && item.error == null) {
                val result = com.vaults.app.scraper.MediaResolver.resolve(currentGalleryType, item.value)
                
                val currentList = _mediaItems.value.toMutableList()
                val index = currentList.indexOfFirst { it.id == item.id }
                if (index != -1) {
                    currentList[index] = item.copy(
                        url = result.url,
                        embedUrl = result.embedUrl,
                        isVideo = result.isVideo,
                        isLoading = false,
                        error = result.error
                    )
                    _mediaItems.value = currentList
                }
            }
        }
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

            val newStates = items.map { MediaItemState.fromGalleryItem(it) }
            val currentList = _mediaItems.value.toMutableList()
            currentList.addAll(newStates)
            _mediaItems.value = currentList

            resolveAllItems(newStates, galleryId)
        }
    }

    suspend fun deleteItem(itemId: Long) = withContext(Dispatchers.IO) {
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
        itemDao.deleteByGalleryId(galleryId)
        dao.deleteById(galleryId)
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

    suspend fun reorderGalleries(galleries: List<Gallery>) = withContext(Dispatchers.IO) {
        galleries.forEachIndexed { index, gallery ->
            dao.updateSortOrder(gallery.id, index)
        }
    }

    fun toggleEditMode() {
        // Edit mode functionality removed
    }
}