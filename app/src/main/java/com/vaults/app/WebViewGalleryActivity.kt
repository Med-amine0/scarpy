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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class WebViewGalleryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWebviewGalleryBinding
    private var galleryId: Long = 0
    private var galleryType: GalleryType = GalleryType.NORMAL

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
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowContentAccess = true
            allowFileAccess = true
            blockNetworkImage = false
            blockNetworkLoads = false
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
            // Show loading
            binding.webView.loadDataWithBaseURL(
                "https://vaults.app/",
                loadingHtml(),
                "text/html",
                "UTF-8",
                null
            )

            // Get gallery type
            val type = withContext(Dispatchers.IO) {
                VaultsApp.instance.db.galleryDao().getGalleryById(galleryId)?.type ?: GalleryType.NORMAL
            }
            galleryType = type

            // Get raw items from DB - skip MediaResolver for faster loading
            // Resolve on-demand in JavaScript
            val itemsJson = JSONArray()
            withContext(Dispatchers.IO) {
                VaultsApp.instance.db.galleryItemDao().getItemsOnce(galleryId).forEach { item ->
                    val obj = JSONObject().apply {
                        put("id", item.id)
                        put("value", item.value)
                        put("type", type.name)
                    }
                    itemsJson.put(obj)
                }
            }

            // Render directly with raw values - JavaScript handles resolution
            loadThumbnailGridFast(itemsJson.toString())
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

    private fun loadThumbnailGridFast(itemsJson: String) {
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
  grid-template-columns: repeat(3, 1fr); 
  gap: 4px; 
  padding: 4px; 
}
.thumb { 
  position: relative; 
  aspect-ratio: 3/4; 
  border-radius: 12px;
  overflow: hidden; 
  background: #1a1a1a;
  cursor: pointer;
}
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
  transition: transform 0.3s;
  transform-origin: center;
}
.fullscreen-content > div, .fullscreen-content iframe, .fullscreen-content video, .fullscreen-content img {
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
  z-index: 1001;
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
</div>
<div class="thumb-grid" id="grid"></div>
<div class="fullscreen" id="fullscreen">
  <button class="close-btn" onclick="closeFullscreen()">×</button>
  <div class="fullscreen-content" id="fullscreenContent" onclick="rotate()"></div>
</div>
<button class="add-btn" onclick="Android.showAddDialog()">+</button>
<script>
var items = $itemsJson;
var galleryType = '$galleryType';
var rotation = 0;

function getHtml(item) {
  var value = item.value;
  var type = galleryType;
  
  if (type === 'REDGIF') {
    var id = value.replace(/[^a-zA-Z0-9]/g, '').substring(0, 12);
    return "<div style='position:relative; padding-bottom:177.78%'><iframe src='https://www.redgifs.com/ifr/" + id + "' frameBorder='0' scrolling='no' width='100%' height='100%' style='position:absolute; top:0; left:0;' allowFullScreen></iframe></div>";
  } else if (type === 'PORNHUB') {
    var id = value.replace(/[^a-zA-Z0-9]/g, '');
    return "<iframe src='https://www.pornhub.com/embedgif/" + id + "' style='width:100%;height:100%;border:none;' allowfullscreen></iframe>";
  } else if (value.match(/\.(mp4|webm)$/i)) {
    return "<video src='" + value + "' autoplay muted loop playsinline style='width:100%;height:100%;object-fit:cover;'></video>";
  } else {
    return "<img src='" + value + "' style='width:100%;height:100%;object-fit:cover;' crossorigin='anonymous'>";
  }
}

function renderGrid() {
  var grid = document.getElementById('grid');
  grid.innerHTML = '';
  
  if (items.length === 0) {
    grid.innerHTML = '<div class="empty-msg">No items yet. Tap + to add URLs.</div>';
    return;
  }
  
  items.forEach(function(item, index) {
    var thumb = document.createElement('div');
    thumb.className = 'thumb';
    thumb.innerHTML = getHtml(item);
    thumb.onclick = function() { openFullscreen(index); };
    grid.appendChild(thumb);
  });
}

function openFullscreen(index) {
  var item = items[index];
  var content = document.getElementById('fullscreenContent');
  var fullscreen = document.getElementById('fullscreen');
  rotation = 0;
  content.style.transform = 'rotate(0deg)';
  content.innerHTML = getHtml(item);
  fullscreen.classList.add('active');
}

function closeFullscreen() {
  document.getElementById('fullscreen').classList.remove('active');
  document.getElementById('fullscreenContent').innerHTML = '';
}

function rotate() {
  rotation = (rotation + 90) % 360;
  document.getElementById('fullscreenContent').style.transform = 'rotate(' + rotation + 'deg)';
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
        fun goBack() {
            finish()
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
        // Try to close fullscreen first, otherwise go back
        binding.webView.evaluateJavascript(
            "if(document.getElementById('fullscreen').classList.contains('active')) { closeFullscreen(); }",
            null
        )
        // Fallback: just finish
        if (!binding.webView.canGoBack()) {
            super.onBackPressed()
        }
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