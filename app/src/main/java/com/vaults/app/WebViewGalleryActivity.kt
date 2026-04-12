package com.vaults.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vaults.app.databinding.ActivityWebviewGalleryBinding
import com.vaults.app.db.GalleryItem
import com.vaults.app.db.GalleryType
import com.vaults.app.scraper.MediaResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class WebViewGalleryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWebviewGalleryBinding
    private var galleryId: Long = 0
    private var galleryType: GalleryType = GalleryType.NORMAL
    private val prefs by lazy { getSharedPreferences("vaults_prefs", Context.MODE_PRIVATE) }

    companion object {
        const val EXTRA_GALLERY_ID = "gallery_id"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        galleryId = intent.getLongExtra(EXTRA_GALLERY_ID, 0)

        setupWebView()
        loadGalleryItems()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowContentAccess = true
            allowFileAccess = true
            blockNetworkImage = false
            blockNetworkLoads = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            mediaPlaybackRequiresUserGesture = false
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: String, lineNumber: Int, sourceID: String) {
                android.util.Log.d("WebViewGallery", "JS: $msg")
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return false
            }
        }
        binding.webView.addJavascriptInterface(Bridge(this), "Android")
    }

    private fun loadGalleryItems() {
        lifecycleScope.launch {
            val type = withContext(Dispatchers.IO) {
                VaultsApp.instance.db.galleryDao().getGalleryById(galleryId)?.type ?: GalleryType.NORMAL
            }
            galleryType = type

            // Build JSON from DB immediately - use cached resolvedUrl where available
            val items = withContext(Dispatchers.IO) {
                VaultsApp.instance.db.galleryItemDao().getItemsOnce(galleryId)
            }

            val nowSeconds = System.currentTimeMillis() / 1000

            val itemsJson = JSONArray()
            items.forEach { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("value", item.value)
                    put("type", type.name)
                    // Use cached URL only if not expired (PH URLs have validto= param)
                    val cached = item.resolvedUrl
                    if (cached != null) {
                        val validTo = Regex("validto=(\\d+)").find(cached)?.groupValues?.getOrNull(1)?.toLongOrNull()
                        if (validTo == null || validTo > nowSeconds) {
                            put("resolvedUrl", cached)
                        }
                        // else expired - treat as uncached, will re-resolve
                    }
                }
                itemsJson.put(obj)
            }

            // Render grid immediately with whatever we have cached
            val defaultVolume = prefs.getInt("default_volume", 5)
            loadThumbnailGridFast(itemsJson.toString(), defaultVolume)

            // Resolve uncached/expired items in parallel, inject into page as each finishes
            if (type == GalleryType.PORNHUB || type == GalleryType.REDGIF) {
                val uncached = items.filter { item ->
                    val cached = item.resolvedUrl
                    if (cached == null) return@filter true
                    val validTo = Regex("validto=(\\d+)").find(cached)?.groupValues?.getOrNull(1)?.toLongOrNull()
                    validTo != null && validTo <= nowSeconds // expired
                }
                uncached.map { item ->
                    async(Dispatchers.IO) {
                        val resolved = MediaResolver.resolve(type, item.value)
                        if (resolved.url != null) {
                            VaultsApp.instance.db.galleryItemDao().updateResolvedUrl(item.id, resolved.url)
                            val escapedUrl = resolved.url.replace("'", "\\'")
                            withContext(Dispatchers.Main) {
                                binding.webView.evaluateJavascript(
                                    "injectResolvedUrl(${item.id}, '$escapedUrl');", null
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private fun loadingHtml(): String = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
body { background: #000; display: flex; justify-content: center; align-items: center; height: 100vh; color: #666; }
</style>
</head>
<body>
<div>Loading...</div>
</body>
</html>
    """.trimIndent()

    private fun loadThumbnailGridFast(itemsJson: String, defaultVolume: Int) {
        val galleryType = this.galleryType.name
        
        val html = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { background: #000; }
.toolbar {
  display: flex;
  align-items: center;
  padding: 12px;
  background: #1e1e1e;
  position: sticky;
  top: 0;
  z-index: 100;
}
.back-btn {
  background: transparent;
  border: none;
  color: #ff69b4;
  font-size: 24px;
  cursor: pointer;
  padding: 8px;
  margin-right: 12px;
}
.thumb-grid { 
  display: grid; 
  grid-template-columns: repeat(var(--cols, 3), 1fr); 
  gap: 4px; 
  padding: 4px; 
}
.thumb { 
  position: relative; 
  border-radius: 12px;
  overflow: hidden; 
  background: #1a1a1a;
  cursor: pointer;
}
.thumb.portrait { aspect-ratio: 3/4; }
.thumb.landscape { aspect-ratio: 16/9; }
.thumb > div, .thumb iframe, .thumb video, .thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 12px;
}
.fullscreen {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: #000;
  display: none;
  justify-content: center;
  align-items: center;
  z-index: 1000;
}
.fullscreen.active { display: flex; }
.fullscreen-content {
  width: 100%;
  height: 100%;
  display: flex;
  justify-content: center;
  align-items: center;
  transform-origin: center;
}
.fullscreen-content video,
.fullscreen-content img {
  width: 100%;
  height: auto;
  max-height: 100%;
  object-fit: contain;
  display: block;
}
.close-btn {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 48px;
  height: 48px;
  background: rgba(255,255,255,0.2);
  border-radius: 24px;
  border: none;
  color: #fff;
  font-size: 24px;
  cursor: pointer;
  z-index: 1001;
}
.add-btn {
  position: fixed;
  bottom: 24px;
  right: 80px;
  width: 56px;
  height: 56px;
  background: #ff69b4;
  border-radius: 28px;
  border: none;
  color: #fff;
  font-size: 28px;
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(0,0,0,0.3);
  z-index: 100;
}
.random-btn {
  display: none;
  position: fixed;
  bottom: 24px;
  right: 24px;
  width: 56px;
  height: 56px;
  background: #2a2a2a;
  border-radius: 28px;
  border: none;
  color: #fff;
  font-size: 24px;
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(0,0,0,0.3);
  z-index: 100;
}
.fullscreen.active .random-btn { display: block; }
.edit-controls {
  display: none;
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  pointer-events: auto;
}
.edit-mode .edit-controls {
  display: flex;
}
.edit-btn {
  position: absolute;
  background: rgba(0,0,0,0.7);
  border: none;
  color: #fff;
  width: 28px;
  height: 28px;
  border-radius: 14px;
  cursor: pointer;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.edit-btn-up { top: 4px; left: 4px; }
.edit-btn-down { top: 4px; right: 4px; }
.edit-btn-delete { bottom: 4px; right: 4px; background: rgba(244,67,54,0.8); }
.empty-msg {
  grid-column: 1/-1;
  text-align: center;
  padding: 40px;
  color: #666;
}
</style>
</head>
<body>
<div class="toolbar">
  <button class="back-btn" onclick="Android.goBack()">←</button>
  <span style="color:#ff69b4;font-size:18px;font-weight:bold;">Gallery</span>
  <button class="unmute-btn" id="unmuteBtn" onclick="toggleMuteAll()" style="display:none;background:transparent;border:none;color:#ff69b4;font-size:20px;cursor:pointer;margin-left:auto;margin-right:8px;">🔇</button>
  <button class="edit-toggle" onclick="toggleEditMode()" style="background:transparent;border:none;color:#ff69b4;font-size:20px;cursor:pointer;">✏️</button>
</div>
<div class="thumb-grid" id="grid"></div>
<div class="fullscreen" id="fullscreen">
  <button class="close-btn" onclick="closeFullscreen()">×</button>
  <div class="fullscreen-content" id="fullscreenContent"></div>
  <button class="random-btn" onclick="showRandomItem()">🎲</button>
</div>
<button class="add-btn" onclick="Android.showAddDialog()">+</button>
<script>
var items = $itemsJson;
var galleryType = '$galleryType';
var defaultVolume = $defaultVolume;
var rotation = 0;
var isMuted = true;

// Show unmute button only for PORNHUB and REDGIF
if (galleryType === 'PORNHUB' || galleryType === 'REDGIF') {
  document.getElementById('unmuteBtn').style.display = 'block';
}

// Toggle mute for all videos in gallery
function toggleMuteAll() {
  isMuted = !isMuted;
  var videos = document.querySelectorAll('.thumb video');
  videos.forEach(function(v) {
    v.muted = isMuted;
    if (!isMuted) v.volume = defaultVolume / 100;
  });
  document.getElementById('unmuteBtn').textContent = isMuted ? '🔇' : '🔊';
}

// Set columns and thumb shape based on type
var grid = document.getElementById('grid');
if (galleryType === 'PORNHUB') {
  grid.style.setProperty('--cols', '2');
} else {
  grid.style.setProperty('--cols', '3');
}
var thumbClass = (galleryType === 'PORNHUB') ? 'thumb landscape' : 'thumb portrait';

// Build all thumb elements once and keep them alive - never recreate
function buildThumbElement(item, index) {
  var thumb = document.createElement('div');
  thumb.className = thumbClass;
  thumb.setAttribute('data-id', item.id);
  thumb.setAttribute('data-index', index);

  var inner = buildMedia(item, false);
  thumb.appendChild(inner);

  // Edit controls (only for Normal and PornHub)
  if (galleryType === 'NORMAL' || galleryType === 'PORNHUB') {
    var editControls = document.createElement('div');
    editControls.className = 'edit-controls';
    
    var btnUp = document.createElement('button');
    btnUp.className = 'edit-btn edit-btn-up';
    btnUp.innerHTML = '↑';
    btnUp.onclick = function(e) { e.stopPropagation(); Android.moveItem(item.id, -1); };
    
    var btnDown = document.createElement('button');
    btnDown.className = 'edit-btn edit-btn-down';
    btnDown.innerHTML = '↓';
    btnDown.onclick = function(e) { e.stopPropagation(); Android.moveItem(item.id, 1); };
    
    var btnDelete = document.createElement('button');
    btnDelete.className = 'edit-btn edit-btn-delete';
    btnDelete.innerHTML = '🗑️';
    btnDelete.onclick = function(e) { e.stopPropagation(); if(confirm('Delete this item?')) Android.deleteItem(item.id); };
    
    editControls.appendChild(btnUp);
    editControls.appendChild(btnDown);
    editControls.appendChild(btnDelete);
    thumb.appendChild(editControls);
  }

  thumb.onclick = function() { openFullscreen(index); };
  return thumb;
}

function buildMedia(item, isFullscreen) {
  var value = item.value;
  var type = galleryType;
  var isEditMode = window.editMode === true;

  // REDGIF: use inline iframe with flexbox centering
  if (type === 'REDGIF') {
    var id = value.includes('redgifs.com') ? value.split('/').pop().split('?')[0] : value;
    id = id.replace(/[^a-zA-Z0-9]/g, '');
    var wrapper = document.createElement('div');
    wrapper.style.cssText = 'display:flex;justify-content:center;align-items:center;width:100%;height:100%;background:#1a1a1a;';
    var iframe = document.createElement('iframe');
    iframe.src = 'https://www.redgifs.com/ifr/' + id;
    iframe.style.cssText = 'width:100%;height:100%;border:none;max-width:300px;';
    iframe.setAttribute('allowfullscreen', '');
    wrapper.appendChild(iframe);
    return wrapper;
  }

  // PORNHUB: video with volume control + long press
  if (type === 'PORNHUB') {
    if (item.resolvedUrl) {
      var v = document.createElement('video');
      v.src = item.resolvedUrl;
      v.autoplay = true;
      v.volume = defaultVolume / 100;
      v.muted = true;
      v.loop = true;
      v.setAttribute('playsinline', '');
      v.style.cssText = isFullscreen
        ? 'width:100%;height:auto;max-height:100%;object-fit:contain;display:block;'
        : 'width:100%;height:100%;object-fit:cover;';
      // Click to adjust volume per-clip in fullscreen
      if (isFullscreen) {
        v.onclick = function(e) {
          e.stopPropagation();
          if (e.clientX < window.innerWidth / 2) {
            // Left side: decrease
            this.volume = Math.max(0, this.volume - 0.1);
          } else {
            // Right side: increase
            this.volume = Math.min(1, this.volume + 0.1);
          }
        };
      }
      // Long press to open WebView
      v.oncontextmenu = function(e) {
        e.preventDefault();
        var id = value.includes('pornhub.com') ? value.split('/').pop().split('?')[0] : value;
        id = id.replace(/[^a-zA-Z0-9]/g, '');
        Android.openInAppUrl('https://www.pornhub.com/gif/' + id);
      };
      return v;
    }
    var placeholder = document.createElement('div');
    placeholder.style.cssText = 'display:flex;align-items:center;justify-content:center;height:100%;color:#555;font-size:11px;';
    placeholder.textContent = 'Loading...';
    return placeholder;
  }

  // Normal video
  if (value.match(/\.(mp4|webm)(\?|$)/i)) {
    var v = document.createElement('video');
    v.src = value;
    v.autoplay = true;
    v.muted = true;
    v.loop = true;
    v.setAttribute('playsinline', '');
    v.style.cssText = isFullscreen
      ? 'width:100%;height:auto;max-height:100%;object-fit:contain;display:block;'
      : 'width:100%;height:100%;object-fit:cover;';
    return v;
  }

  // Normal image
  var img = document.createElement('img');
  img.src = value;
  img.style.cssText = isFullscreen
    ? 'width:100%;height:auto;max-height:100%;object-fit:contain;display:block;'
    : 'width:100%;height:100%;object-fit:cover;';
  img.onerror = function() { this.style.opacity = '0.3'; };
  return img;
}

// Called from Kotlin when a background resolution finishes - swap placeholder for real video
function injectResolvedUrl(itemId, url) {
  for (var i = 0; i < items.length; i++) {
    if (items[i].id == itemId) {
      items[i].resolvedUrl = url;
      var cell = document.querySelector('[data-id="' + itemId + '"]');
      if (cell) {
        cell.innerHTML = '';
        cell.appendChild(buildMedia(items[i], false));
      }
      break;
    }
  }
}

function renderGrid() {
  grid.innerHTML = '';
  if (items.length === 0) {
    grid.innerHTML = '<div class="empty-msg">No items yet. Tap + to add URLs.</div>';
    return;
  }
  items.forEach(function(item, index) {
    grid.appendChild(buildThumbElement(item, index));
  });
}

function openFullscreen(index) {
  var item = items[index];
  var type = galleryType;
  var content = document.getElementById('fullscreenContent');
  var fullscreen = document.getElementById('fullscreen');

  content.innerHTML = '';

  // RedGif: 1st click expands inline, 2nd click opens InAppBrowserActivity
  if (type === 'REDGIF') {
    var id = item.value.includes('redgifs.com') ? item.value.split('/').pop().split('?')[0] : item.value;
    id = id.replace(/[^a-zA-Z0-9]/g, '');
    
    if (!item.wasExpanded) {
      // First click: expand inline
      item.wasExpanded = true;
      content.innerHTML = '';
      var wrapper = document.createElement('div');
      wrapper.style.cssText = 'width:100%;height:100%;display:flex;justify-content:center;align-items:center;background:#000;';
      var iframe = document.createElement('iframe');
      iframe.src = 'https://www.redgifs.com/ifr/' + id;
      iframe.style.cssText = 'width:100%;height:100%;max-width:500px;border:none;';
      iframe.setAttribute('allowfullscreen', '');
      wrapper.appendChild(iframe);
      content.appendChild(wrapper);
      fullscreen.classList.add('active');
      // Add click handler on expanded content to open InAppBrowserActivity
      content.onclick = function(e) {
        if (item.wasExpanded) {
          Android.openInAppUrl('https://www.redgifs.com/ifr/' + id);
          item.wasExpanded = false;
          closeFullscreen();
        }
      };
      return;
    } else {
      // Second click: open InAppBrowserActivity
      Android.openInAppUrl('https://www.redgifs.com/ifr/' + id);
      item.wasExpanded = false;
      return;
    }
  }
  
  // PornHub: inline fullscreen with landscape orientation
  if (type === 'PORNHUB' && item.resolvedUrl) {
    Android.requestLandscape();
    content.appendChild(buildMedia(item, true));
    fullscreen.classList.add('active');
    // Reset orientation on close
    window.resetOrientation = function() { Android.resetOrientation(); };
    return;
  }

  // Normal image/video: show inline fullscreen
  content.appendChild(buildMedia(item, true));
  fullscreen.classList.add('active');
  
  // Add rotation to fullscreen content for images
  var rotation = 0;
  content.onclick = function() {
    rotation = (rotation + 90) % 360;
    content.style.transform = 'rotate(' + rotation + 'deg') + ' scale(1)';
    var img = content.querySelector('img');
    if (img) { img.style.objectFit = 'contain'; }
  };
}
  if (type === 'PORNHUB' && item.resolvedUrl) {
    Android.openInAppUrl(item.resolvedUrl);
    return;
  }

  // Normal image/video: show inline fullscreen
  content.appendChild(buildMedia(item, true));
  fullscreen.classList.add('active');
  
  // Add rotation to fullscreen content for images
  var rotation = 0;
  content.onclick = function() {
    rotation = (rotation + 90) % 360;
    content.style.transform = 'rotate(' + rotation + 'deg)';
  };
}

function closeFullscreen() {
  document.getElementById('fullscreen').classList.remove('active');
  document.getElementById('fullscreenContent').innerHTML = '';
  // Reset orientation and mute state
  if (galleryType === 'PORNHUB') {
    Android.resetOrientation();
    isMuted = true;
    document.getElementById('unmuteBtn').textContent = '🔇';
  }
}

// Edit mode toggle
function toggleEditMode() {
  window.editMode = !window.editMode;
  document.body.classList.toggle('edit-mode', window.editMode);
}

// Random next with consumable list
var shownIndices = [];
function showRandomItem() {
  if (items.length === 0) return;
  
  var available = [];
  for (var i = 0; i < items.length; i++) {
    if (!shownIndices.includes(i)) available.push(i);
  }
  
  // Reset when all shown
  if (available.length === 0) {
    shownIndices = [];
    available = [...Array(items.length).keys()];
  }
  
  var randomIndex = available[Math.floor(Math.random() * available.length)];
  shownIndices.push(randomIndex);
  openFullscreen(randomIndex);
}

renderGrid();
</script>
</body>
</html>
        """.trimIndent()

        binding.webView.loadDataWithBaseURL("https://app.vaults.local", html, "text/html", "UTF-8", null)
    }

    inner class Bridge(private val context: Context) {
        @JavascriptInterface
        fun deleteItem(itemId: Long) {
            lifecycleScope.launch {
                VaultsApp.instance.db.galleryItemDao().deleteById(itemId)
                loadGalleryItems()
            }
        }

        @JavascriptInterface
        fun moveItem(itemId: Long, direction: Int) {
            lifecycleScope.launch(Dispatchers.IO) {
                val items = VaultsApp.instance.db.galleryItemDao().getItemsOnce(galleryId)
                val currentItem = items.find { it.id == itemId }
                if (currentItem != null) {
                    val currentIndex = items.indexOf(currentItem)
                    val newIndex = currentIndex + direction
                    if (newIndex in items.indices) {
                        val otherItem = items[newIndex]
                        val tempSort = currentItem.sortOrder
                        VaultsApp.instance.db.galleryItemDao().updateSortOrder(itemId, otherItem.sortOrder)
                        VaultsApp.instance.db.galleryItemDao().updateSortOrder(otherItem.id, tempSort)
                    }
                }
                withContext(Dispatchers.Main) {
                    loadGalleryItems()
                }
            }
        }

        @JavascriptInterface
        fun goBack() {
            finish()
        }

        @JavascriptInterface
        fun openInAppUrl(url: String) {
            val intent = android.content.Intent(context, InAppBrowserActivity::class.java)
            intent.putExtra("url", url)
            context.startActivity(intent)
        }

        @JavascriptInterface
        fun requestLandscape() {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        @JavascriptInterface
        fun resetOrientation() {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        @JavascriptInterface
        fun showAddDialog() {
            val input = android.widget.EditText(context)
            input.hint = "Enter URLs (comma or newline separated)"
            input.setTextColor(android.graphics.Color.WHITE)
            input.setBackgroundColor(android.graphics.Color.parseColor("#2a2a2a"))

            android.app.AlertDialog.Builder(context)
                .setTitle("Add Media")
                .setView(input)
                .setPositiveButton("Add") { _, _ ->
                    val text = input.text.toString()
                    if (text.isNotBlank()) {
                        addItems(text)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun addItems(text: String) {
            lifecycleScope.launch {
                val values = text.replace("\"", "")
                    .split(",", "\n", "\r\n")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()

                val existing = withContext(Dispatchers.IO) {
                    VaultsApp.instance.db.galleryItemDao().getExistingValues(galleryId).toSet()
                }
                val newValues = values.filter { it !in existing }

                val currentMax = withContext(Dispatchers.IO) {
                    VaultsApp.instance.db.galleryItemDao().getItemsOnce(galleryId)
                        .maxOfOrNull { it.sortOrder } ?: -1
                }

                val items = newValues.mapIndexed { index, value ->
                    GalleryItem(
                        galleryId = galleryId,
                        value = value,
                        sortOrder = currentMax + index + 1
                    )
                }

                withContext(Dispatchers.IO) {
                    VaultsApp.instance.db.galleryItemDao().insertAll(items)
                }

                loadGalleryItems()
            }
        }
    }

    override fun onBackPressed() {
        // Check if fullscreen is active - only finish if it's not open
        binding.webView.evaluateJavascript(
            "document.getElementById('fullscreen').classList.contains('active')",
            { result ->
                // If result is "false" or null, fullscreen is NOT active, so finish
                if (result == null || result == "false") {
                    finish()
                }
                // If result is "true", fullscreen was closed by the JS, stay in activity
            }
        )
    }
}

data class ItemWithMedia(
    val id: Long,
    val value: String,
    val url: String?,
    val embedUrl: String?,
    val isVideo: Boolean,
    val error: String?
)