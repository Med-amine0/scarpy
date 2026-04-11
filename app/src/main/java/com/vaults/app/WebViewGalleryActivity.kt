package com.vaults.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vaults.app.databinding.ActivityWebviewGalleryBinding
import com.vaults.app.db.GalleryItem
import com.vaults.app.db.GalleryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class WebViewGalleryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWebviewGalleryBinding
    private var galleryId: Long = 0
    private var galleryType: GalleryType = GalleryType.NORMAL
    
    private var currentRotation = 0
    private var isFullscreen = false

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
        }
        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.webViewClient = object : WebViewClient() {}
        binding.webView.addJavascriptInterface(Bridge(this), "Android")
    }

    private fun loadGalleryItems() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                VaultsApp.instance.db.galleryItemDao().getItemsOnce(galleryId)
            }
            
            val type = withContext(Dispatchers.IO) {
                VaultsApp.instance.db.galleryDao().getGalleryById(galleryId)?.type ?: GalleryType.NORMAL
            }
            galleryType = type

            val itemsJson = JSONArray()
            items.forEach { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("value", item.value)
                    put("type", type.name)
                }
                itemsJson.put(obj)
            }
            
            loadThumbnailGrid(itemsJson.toString())
        }
    }

    private fun loadThumbnailGrid(itemsJson: String) {
        val html = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { background: #000; }
.thumb-grid { 
  display: grid; 
  grid-template-columns: repeat(3, 1fr); 
  gap: 4px; 
  padding: 4px; 
}
.thumb { 
  position: relative; 
  aspect-ratio: 3/4; 
  border-radius: 8px; 
  overflow: hidden; 
  background: #1a1a1a;
}
.thumb img, .thumb video { 
  width: 100%; 
  height: 100%; 
  object-fit: cover; 
}
.play-icon {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 36px;
  height: 36px;
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
.media-container {
  max-width: 100%;
  max-height: 100%;
  transition: transform 0.3s;
}
.media-container img, .media-container video, .media-container iframe {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
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
}
.add-btn {
  position: fixed;
  bottom: 24px;
  right: 24px;
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
</style>
</head>
<body>
<div class="thumb-grid" id="grid"></div>
<div class="fullscreen" id="fullscreen">
  <button class="close-btn" onclick="closeFullscreen()">×</button>
  <div class="media-container" id="mediaContainer" onclick="rotate()"></div>
</div>
<button class="add-btn" onclick="Android.showAddDialog()">+</button>
<script>
var items = $itemsJson;
var rotation = 0;

function renderGrid() {
  var grid = document.getElementById('grid');
  grid.innerHTML = '';
  items.forEach(function(item, index) {
    var thumb = document.createElement('div');
    thumb.className = 'thumb';
    thumb.onclick = function() { openFullscreen(index); };
    
    var mediaType = item.type;
    var value = item.value;
    
    if (mediaType === 'PORNHUB') {
      var img = document.createElement('img');
      var id = value.replace(/[^a-zA-Z0-9]/g, '');
      img.src = 'https://thumb-videos1.pornhub.com/thumbs/' + id + '.jpg';
      img.onerror = function() { 
        this.style.display = 'none';
        var play = document.createElement('img');
        play.className = 'play-icon';
        play.src = 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0iI2ZmZiI+PHBhdGggZD0iTTEgMS41M3YxOWgyMnYtMTlIMXYtMS41M3pNNCA0djE2bDkgNS01IDV2LTEyTDE0IDR6Ii8+PC9zdmc+';
        thumb.appendChild(play);
      };
      thumb.appendChild(img);
    } else if (mediaType === 'REDGIF') {
      var img = document.createElement('img');
      var id = value.replace(/[^a-zA-Z0-9]/g, '').substring(0, 12);
      img.src = 'https://thumbs.redgifs.com/' + id + '-poster.jpg';
      img.onerror = function() {
        this.style.display = 'none';
        var play = document.createElement('img');
        play.className = 'play-icon';
        play.src = 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0iI2ZmZiI+PHBhdGggZD0iTTEgMS41M3YxOWgyMnYtMTlIMXYtMS41M3pNNCA0djE2bDkgNS01IDV2LTEyTDE0IDR6Ii8+PC9zdmc+';
        thumb.appendChild(play);
      };
      thumb.appendChild(img);
    } else if (value.endsWith('.mp4') || value.endsWith('.webm')) {
      var video = document.createElement('video');
      video.src = value;
      video.autoplay = true;
      video.muted = true;
      video.loop = true;
      video.playsinline = true;
      thumb.appendChild(video);
    } else {
      var img = document.createElement('img');
      img.src = value;
      img.onerror = function() { this.style.display = 'none'; };
      thumb.appendChild(img);
    }
    grid.appendChild(thumb);
  });
}

function openFullscreen(index) {
  var item = items[index];
  var container = document.getElementById('mediaContainer');
  var fullscreen = document.getElementById('fullscreen');
  rotation = 0;
  container.style.transform = 'rotate(0deg)';
  
  if (item.type === 'PORNHUB') {
    var id = item.value.replace(/[^a-zA-Z0-9]/g, '');
    container.innerHTML = '<iframe src="https://www.pornhub.com/embedgif/' + id + '" frameborder="0" allowfullscreen style="width:100%;height:100%;"></iframe>';
  } else if (item.type === 'REDGIF') {
    var id = item.value.replace(/[^a-zA-Z0-9]/g, '').substring(0, 12);
    container.innerHTML = '<iframe src="https://www.redgifs.com/ifr/' + id + '" frameborder="0" allowfullscreen style="width:100%;height:100%;"></iframe>';
  } else if (item.value.endsWith('.mp4') || item.value.endsWith('.webm')) {
    container.innerHTML = '<video src="' + item.value + '" autoplay loop muted playsinline style="width:100%;height:100%;object-fit:contain;"></video>';
  } else {
    container.innerHTML = '<img src="' + item.value + '" style="width:100%;height:100%;object-fit:contain;">';
  }
  
  fullscreen.classList.add('active');
}

function closeFullscreen() {
  document.getElementById('fullscreen').classList.remove('active');
  document.getElementById('mediaContainer').innerHTML = '';
}

function rotate() {
  rotation = (rotation + 90) % 360;
  document.getElementById('mediaContainer').style.transform = 'rotate(' + rotation + 'deg)';
}

renderGrid();
</script>
</body>
</html>
        """.trimIndent()

        binding.webView.loadDataWithBaseURL("https://vaults.app/", html, "text/html", "UTF-8", null)
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

                val currentMax = withContext(Dispatchers.IO) {
                    VaultsApp.instance.db.galleryItemDao().getItemsOnce(galleryId)
                        .maxOfOrNull { it.sortOrder } ?: -1
                }

                val items = values.mapIndexed { index, value ->
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
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}