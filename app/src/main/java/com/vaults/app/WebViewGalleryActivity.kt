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
            loadThumbnailGridFast(itemsJson.toString())

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

// Set columns and thumb shape based on type
var grid = document.getElementById('grid');
if (galleryType === 'PORNHUB') {
  grid.style.setProperty('--cols', '2');
} else {
  grid.style.setProperty('--cols', '3');
}
var thumbClass = (galleryType === 'PORNHUB') ? 'thumb landscape' : 'thumb portrait';

function getHtml(item) {
  var value = item.value;
  var type = galleryType;

  if (type === 'REDGIF' || type === 'PORNHUB') {
    if (item.resolvedUrl) {
      return "<video src='" + item.resolvedUrl + "' autoplay muted loop playsinline style='width:100%;height:100%;object-fit:cover;'></video>";
    }
    return "<div style='display:flex;align-items:center;justify-content:center;height:100%;color:#555;font-size:11px;'>Loading...</div>";
  } else if (value.match(/\.(mp4|webm)(\?|$)/i)) {
    return "<video src='" + value + "' autoplay muted loop playsinline style='width:100%;height:100%;object-fit:cover;'></video>";
  } else {
    return "<img src='" + value + "' style='width:100%;height:100%;object-fit:cover;' loading='lazy' onerror=\"this.style.opacity='0.3'\">";
  }
}

// Called from Kotlin when a background resolution finishes
function injectResolvedUrl(itemId, url) {
  for (var i = 0; i < items.length; i++) {
    if (items[i].id === itemId) {
      items[i].resolvedUrl = url;
      var cell = document.querySelector('[data-id="' + itemId + '"]');
      if (cell) cell.innerHTML = getHtml(items[i]);
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
    var thumb = document.createElement('div');
    thumb.className = thumbClass;
    thumb.setAttribute('data-id', item.id);
    thumb.innerHTML = getHtml(item);
    thumb.onclick = function() {
      if (galleryType === 'REDGIF' && item.resolvedUrl) {
        var id = item.value.includes('redgifs.com') ? item.value.split('/').pop().split('?')[0] : item.value;
        id = id.replace(/[^a-zA-Z0-9]/g, '');
        Android.openInAppUrl('https://www.redgifs.com/ifr/' + id);
      } else {
        openFullscreen(index);
      }
    };
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