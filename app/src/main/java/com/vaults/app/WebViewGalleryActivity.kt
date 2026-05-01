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
import kotlinx.coroutines.runBlocking
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
        // Edge-to-edge: draw behind status bar
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
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

            val items = withContext(Dispatchers.IO) {
                VaultsApp.instance.db.galleryItemDao().getItemsOnce(galleryId)
            }

            val nowSeconds = System.currentTimeMillis() / 1000

            // Build initial JSON — pass cached/valid resolvedUrls so they render immediately
            val itemsJson = JSONArray()
            items.forEach { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("value", item.value)
                    put("type", type.name)
                    put("weight", item.weight)
                    // Compute .md.jpg thumbnail URL if useMd is set
                    if (item.useMd) {
                        val thumb = item.value.replace(Regex("\\.jpg$", RegexOption.IGNORE_CASE), ".md.jpg")
                        put("thumbUrl", thumb)
                    }
                    val cached = item.resolvedUrl
                    if (cached != null) {
                        val validTo = Regex("validto=(\\d+)").find(cached)?.groupValues?.getOrNull(1)?.toLongOrNull()
                        if (validTo == null || validTo > nowSeconds) {
                            put("resolvedUrl", cached)
                        }
                    }
                }
                itemsJson.put(obj)
            }

            val defaultVolume = prefs.getInt("default_volume", 5)
            val gallery = withContext(Dispatchers.IO) {
                VaultsApp.instance.db.galleryDao().getGalleryById(galleryId)
            }
            val savedCols = gallery?.columnCount ?: 0
            val galleryName = gallery?.name ?: "Gallery"
            loadThumbnailGridFast(itemsJson.toString(), defaultVolume, savedCols, galleryName, items.size)

            // Preload all MD thumbnails in parallel — they're small (~100kb) so fire all at once
            if (type == GalleryType.NORMAL) {
                val mdItems = items.filter { it.useMd }
                if (mdItems.isNotEmpty()) {
                    val urlsJs = mdItems.joinToString(",") { "\"${it.value.replace(Regex("\\.jpg$", RegexOption.IGNORE_CASE), ".md.jpg")}\"" }
                    withContext(Dispatchers.Main) {
                        binding.webView.evaluateJavascript(
                            "(function(){[$urlsJs].forEach(function(u){var i=new Image();i.src=u;});})();", null
                        )
                    }
                }
            }

            // Resolve uncached/expired items in parallel — fast, all at once
            if (type == GalleryType.REDGIF) {
                val uncached = items.filter { item ->
                    val cached = item.resolvedUrl
                    if (cached == null) return@filter true
                    val validTo = Regex("validto=(\\d+)").find(cached)?.groupValues?.getOrNull(1)?.toLongOrNull()
                    validTo != null && validTo <= nowSeconds
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

    private fun loadThumbnailGridFast(itemsJson: String, defaultVolume: Int, savedCols: Int = 0, galleryName: String = "Gallery", itemCount: Int = 0) {
        val galleryType = this.galleryType.name
        val escapedName = galleryName.replace("\"", "\\\"").replace("'", "\\'")

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
  padding-top: calc(12px + env(safe-area-inset-top));
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
  top: 0; left: 0;
  width: 100%; height: 100%;
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
  overflow: hidden;
}
.close-btn {
  position: absolute;
  top: 16px; right: 16px;
  width: 48px; height: 48px;
  background: rgba(255,255,255,0.2);
  border-radius: 24px;
  border: none;
  color: #fff;
  font-size: 24px;
  cursor: pointer;
  z-index: 1001;
}
.thumb.selected::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: 12px;
  border: 3px solid #ff69b4;
  background: rgba(255,105,180,0.18);
  pointer-events: none;
  z-index: 4;
}
.thumb.selected .select-check {
  display: flex;
}
.select-check {
  display: none;
  position: absolute;
  top: 6px; left: 6px;
  width: 22px; height: 22px;
  border-radius: 11px;
  background: #ff69b4;
  color: #fff;
  font-size: 13px;
  align-items: center;
  justify-content: center;
  z-index: 5;
  pointer-events: none;
}
#copy-toast {
  position: fixed;
  bottom: 80px; left: 50%; transform: translateX(-50%);
  background: rgba(255,105,180,0.92);
  color: #fff;
  padding: 8px 20px;
  border-radius: 20px;
  font-size: 13px;
  z-index: 9999;
  opacity: 0;
  transition: opacity 0.2s;
  pointer-events: none;
}
#copy-toast.show { opacity: 1; }
.edit-controls {
  display: none;
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  pointer-events: auto;
}
.edit-mode .edit-controls { display: flex; }
.edit-btn {
  position: absolute;
  background: rgba(0,0,0,0.7);
  border: none;
  color: #fff;
  width: 28px; height: 28px;
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
.edit-btn-md { bottom: 4px; left: 4px; font-size: 11px; background: rgba(0,120,255,0.8); }
.thumb.is-md .edit-btn-md { background: rgba(255,105,180,0.9); }
#swipe-expand {
  display: none;
  position: fixed;
  top: 0; left: 0; width: 100%; height: 100%;
  background: #000;
  z-index: 9999;
  align-items: center;
  justify-content: center;
}
#swipe-expand.active { display: flex; }
#swipe-expand img {
  max-width: 100%; max-height: 100%;
  object-fit: contain;
}
#swipe-expand-close {
  position: absolute;
  top: 16px; right: 16px;
  width: 44px; height: 44px;
  background: rgba(255,255,255,0.2);
  border-radius: 22px;
  border: none; color: #fff; font-size: 22px;
  cursor: pointer; z-index: 10000;
}
.empty-msg {
  grid-column: 1/-1;
  text-align: center;
  padding: 40px;
  color: #666;
}
.col-controls {
  display: none;
  align-items: center;
  gap: 6px;
  margin-left: 8px;
}
.edit-mode-active .col-controls { display: flex; }
.col-btn {
  background: #2a2a2a;
  border: none;
  color: #fff;
  width: 28px; height: 28px;
  border-radius: 14px;
  font-size: 16px;
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
}
.col-count { color: #ff69b4; font-size: 14px; min-width: 16px; text-align: center; }

/* ── Swipe / Tinder mode ─────────────────────────────────────────────────── */
#swipe-view {
  display: none;
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  flex-direction: column;
  background: #0a0a0a;
  z-index: 200;
  padding-top: calc(56px + env(safe-area-inset-top));
  padding-bottom: 100px;
}
#swipe-view.active { display: flex; }
#swipe-toolbar {
  position: fixed;
  top: 0; left: 0; right: 0;
  display: flex;
  align-items: center;
  padding: 12px;
  padding-top: calc(12px + env(safe-area-inset-top));
  background: #1e1e1e;
  z-index: 201;
}
#card-stack {
  flex: 1;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}
.swipe-card {
  position: absolute;
  width: 96%;
  max-height: 84vh;
  border-radius: 22px;
  overflow: hidden;
  background: #1a1a1a;
  box-shadow: 0 12px 40px rgba(0,0,0,0.7);
  touch-action: none;
  user-select: none;
  transform-origin: center bottom;
  will-change: transform;
}
.swipe-card video, .swipe-card iframe, .swipe-card > div {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.swipe-card img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  display: block;
  background: #000;
}
.swipe-card .card-inner {
  width: 100%;
  aspect-ratio: 3/4;
  position: relative;
}
.swipe-card.landscape .card-inner { aspect-ratio: 16/9; }
.swipe-stamp {
  position: absolute;
  top: 28px;
  font-size: 52px;
  font-weight: 900;
  border: 5px solid;
  border-radius: 10px;
  padding: 6px 20px;
  opacity: 0;
  pointer-events: none;
  text-transform: uppercase;
  letter-spacing: 2px;
  transition: opacity 0.05s;
}
.swipe-stamp.like  { left: 20px;  color: #4caf50; border-color: #4caf50; transform: rotate(-18deg); }
.swipe-stamp.nope  { right: 20px; color: #f44336; border-color: #f44336; transform: rotate(18deg); }
#swipe-actions {
  position: fixed;
  bottom: 24px; left: 0; right: 0;
  display: flex;
  justify-content: center;
  gap: 40px;
  z-index: 201;
}
.swipe-action-btn {
  width: 68px; height: 68px;
  border-radius: 34px;
  border: none;
  font-size: 28px;
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 4px 20px rgba(0,0,0,0.5);
  transition: transform 0.15s;
}
.swipe-action-btn:active { transform: scale(0.9); }
#btn-nope   { background: #1e1e1e; color: #f44336; }
#btn-like   { background: #1e1e1e; color: #4caf50; }
#btn-burn   { background: #1e1e1e; color: #ff6600; font-size: 24px; }
#swipe-counter {
  color: #555;
  font-size: 13px;
  text-align: center;
  margin-top: 6px;
}
.swipe-card-back {
  position: absolute;
  width: 96%;
  border-radius: 22px;
  overflow: hidden;
  background: #1a1a1a;
  box-shadow: 0 4px 20px rgba(0,0,0,0.4);
  pointer-events: none;
  will-change: transform;
}
.swipe-card-back .card-inner { aspect-ratio: 3/4; }
.swipe-card-back.landscape .card-inner { aspect-ratio: 16/9; }

/* ── Flip Swipe View (Toggle Rotation) ─────────────────────────────────────── */
#swipe-view.flipped {
  flex-direction: row;
  padding: 12px;
  padding-left: calc(12px + env(safe-area-inset-left));
  padding-right: calc(12px + env(safe-area-inset-right));
}
#swipe-view.flipped #swipe-toolbar {
  position: fixed;
  left: 12px; top: 50%;
  transform: translateY(-50%);
  flex-direction: column;
  padding: 12px 8px;
  justify-content: center;
  gap: 16px;
}
#swipe-view.flipped #swipe-toolbar .back-btn {
  transform: rotate(-90deg);
  margin: 0;
}
#swipe-view.flipped #swipe-toolbar #flipSwipeBtn {
  transform: rotate(-90deg);
}
#swipe-view.flipped #swipe-toolbar #individualMuteBtn {
  transform: rotate(-90deg);
}
#swipe-view.flipped #card-stack {
  margin: 0;
}
#swipe-view.flipped .swipe-card {
  width: 100%;
  max-width: 100vw;
  max-height: 100vh;
  border-radius: 12px;
}
#swipe-view.flipped .swipe-card-back {
  width: 100%;
  max-width: 100vw;
}
#swipe-view.flipped #swipe-actions {
  flex-direction: column;
  position: fixed;
  right: 12px; top: 50%;
  transform: translateY(-50%);
  justify-content: center;
  gap: 20px;
  padding: 12px 8px;
}
#swipe-view.flipped .swipe-action-btn {
  width: 40px; height: 40px;
  font-size: 18px;
}
#swipe-view.flipped #swipe-counter {
  position: fixed;
  bottom: 12px; left: 50%;
  transform: translateX(-50%) rotate(-90deg);
  transform-origin: center;
  width: max-content;
  margin: 0 auto;
}

/* ── CLIPS Sideways Swipe Mode ─────────────────────────────────────────── */
#swipe-view.clips-landscape {
  flex-direction: row;
  padding: 12px;
  padding-left: calc(12px + env(safe-area-inset-left));
  padding-right: calc(12px + env(safe-area-inset-right));
}
#swipe-view.clips-landscape #swipe-toolbar {
  flex-direction: column;
  position: fixed;
  left: 0; top: 0; bottom: 0;
  width: 56px;
  right: auto;
  padding: 12px 8px;
  justify-content: flex-start;
  gap: 16px;
  border-right: 1px solid #333;
}
#swipe-view.clips-landscape #swipe-toolbar .back-btn {
  transform: rotate(-90deg);
  margin: 0;
}
#swipe-view.clips-landscape #card-stack {
  margin: 0 56px;
}
#swipe-view.clips-landscape .swipe-card {
  width: 100%;
  max-width: calc(100vw - 112px);
  max-height: 100vh;
  border-radius: 12px;
}
#swipe-view.clips-landscape .swipe-card-back {
  width: 100%;
  max-width: calc(100vw - 112px);
}
#swipe-view.clips-landscape #swipe-actions {
  flex-direction: column;
  left: auto; right: 0; top: 0; bottom: 0;
  width: 56px;
  justify-content: center;
  gap: 24px;
  padding: 12px 8px;
  background: #1e1e1e;
  border-left: 1px solid #333;
}
#swipe-view.clips-landscape .swipe-action-btn {
  width: 40px; height: 40px;
  font-size: 20px;
}
#swipe-view.clips-landscape #swipe-counter {
  position: fixed;
  bottom: 12px; left: 56px; right: 56px;
  transform: rotate(-90deg);
  transform-origin: center;
  width: max-content;
  margin: 0 auto;
}

/* ── Particle burst ──────────────────────────────────────────────────────── */
.particle {
  position: fixed;
  pointer-events: none;
  z-index: 999;
  font-size: 28px;
  animation: burst 0.7s ease-out forwards;
}
@keyframes burst {
  0%   { transform: translate(0,0) scale(1);   opacity: 1; }
  100% { transform: translate(var(--tx), var(--ty)) scale(0.3); opacity: 0; }
}
</style>
</head>
<body>
<div class="toolbar" id="toolbar">
  <button class="back-btn" onclick="Android.goBack()">←</button>
  <span style="color:#ff69b4;font-size:16px;font-weight:bold;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:140px;">$escapedName</span>
  <span style="color:#888;font-size:13px;margin-left:4px;white-space:nowrap;">($itemCount)</span>
  <div class="col-controls">
    <button class="col-btn" onclick="changeColumns(-1)">−</button>
    <span class="col-count" id="colCount">3</span>
    <button class="col-btn" onclick="changeColumns(1)">+</button>
  </div>
  <button id="unmuteBtn" onclick="toggleMuteAll()" style="display:none;background:transparent;border:none;color:#ff69b4;font-size:20px;cursor:pointer;margin-left:auto;margin-right:8px;">🔇</button>
  <button onclick="Android.exportItems()" style="background:transparent;border:none;color:#ff69b4;font-size:20px;cursor:pointer;margin-left:auto;margin-right:8px;" title="Export">📤</button>
  <button onclick="enterSwipeMode()" style="background:transparent;border:none;color:#ff69b4;font-size:20px;cursor:pointer;margin-right:8px;" title="Swipe mode">🃏</button>
  <button onclick="Android.showAddDialog()" style="background:transparent;border:none;color:#ff69b4;font-size:22px;cursor:pointer;margin-right:8px;" title="Add">＋</button>
  <button id="btn-delete-sel" onclick="deleteSelected()" style="display:none;background:transparent;border:none;color:#f44336;font-size:20px;cursor:pointer;margin-right:8px;" title="Delete selected">🗑️</button>
  <button onclick="toggleEditMode()" style="background:transparent;border:none;color:#ff69b4;font-size:20px;cursor:pointer;">✏️</button>
</div>
<div class="thumb-grid" id="grid"></div>
<div class="fullscreen" id="fullscreen">
  <button class="close-btn" onclick="closeFullscreen()">×</button>
  <div class="fullscreen-content" id="fullscreenContent"></div>
</div>
<div id="copy-toast">URL copied</div>

<!-- Swipe expand overlay — stays inside swipe mode -->
<div id="swipe-expand">
  <button id="swipe-expand-close" onclick="closeSwipeExpand()">×</button>
  <img id="swipe-expand-img" src="" />
</div>

<!-- Tinder swipe view -->
<div id="swipe-view">
  <div id="swipe-toolbar">
    <button class="back-btn" onclick="exitSwipeMode()">←</button>
    <button id="flipSwipeBtn" onclick="toggleSwipeFlip()" style="background:transparent;border:none;color:#ff69b4;font-size:20px;cursor:pointer;" title="Flip">🔄</button>
    <span style="color:#ff69b4;font-size:18px;font-weight:bold;flex:1;">Swipe</span>
    <button id="individualMuteBtn" onclick="toggleIndividualMute()" style="display:none;background:transparent;border:none;color:#ff69b4;font-size:20px;cursor:pointer;" title="Individual Mute">🔇</button>
  </div>
  <div id="card-stack"></div>
  <div id="swipe-counter"></div>
  <div id="swipe-actions">
    <button class="swipe-action-btn" id="btn-nope"  onclick="programmaticSwipe('left')">👎</button>
    <button class="swipe-action-btn" id="btn-del"   onclick="deleteCurrentSwipeItem()" style="background:#1e1e1e;color:#f44336;font-size:20px;" title="Delete">✕</button>
    <button class="swipe-action-btn" id="btn-burn"  onclick="burnCurrent()" title="Never show again">🔥</button>
    <button class="swipe-action-btn" id="btn-like"  onclick="programmaticSwipe('right')">👍</button>
  </div>
</div>
<script>
var items = $itemsJson;
var galleryType = '$galleryType';
var defaultVolume = $defaultVolume;
var isMuted = true;

if (galleryType === 'CLIPS' || galleryType === 'REDGIF') {
  document.getElementById('unmuteBtn').style.display = 'block';
}

function toggleMuteAll() {
  isMuted = !isMuted;
  document.querySelectorAll('#grid video').forEach(function(v) {
    v.muted = isMuted;
    if (!isMuted) { v.volume = defaultVolume / 100; }
  });
  document.getElementById('unmuteBtn').textContent = isMuted ? '🔇' : '🔊';
}

var grid = document.getElementById('grid');
var defaultCols = (galleryType === 'CLIPS' || galleryType === 'REDGIF') ? 2 : 3;
var savedCols = $savedCols;
var currentCols = savedCols > 0 ? savedCols : defaultCols;
grid.style.setProperty('--cols', currentCols);
document.getElementById('colCount').textContent = currentCols;
var thumbClass = (galleryType === 'CLIPS') ? 'thumb landscape' : 'thumb portrait';

function changeColumns(delta) {
  currentCols = Math.max(1, Math.min(6, currentCols + delta));
  grid.style.setProperty('--cols', currentCols);
  document.getElementById('colCount').textContent = currentCols;
  Android.saveColumnCount(currentCols);
}

function buildThumbElement(item, index) {
  var thumb = document.createElement('div');
  thumb.className = thumbClass + (item.thumbUrl ? ' is-md' : '');
  thumb.setAttribute('data-id', item.id);
  thumb.setAttribute('data-index', index);
  thumb.appendChild(buildMedia(item, false));

  // Selection checkmark (visible when thumb.selected)
  var check = document.createElement('div');
  check.className = 'select-check';
  check.textContent = '✓';
  thumb.appendChild(check);

  if (galleryType === 'NORMAL' || galleryType === 'CLIPS' || galleryType === 'REDGIF') {
    var ec = document.createElement('div');
    ec.className = 'edit-controls';
    var bu = document.createElement('button'); bu.className = 'edit-btn edit-btn-up'; bu.innerHTML = '↑';
    bu.onclick = (function(id, idx) { return function(e) { e.stopPropagation(); moveItemInGrid(id, idx, -1); }; })(item.id, index);
    var bd = document.createElement('button'); bd.className = 'edit-btn edit-btn-down'; bd.innerHTML = '↓';
    bd.onclick = (function(id, idx) { return function(e) { e.stopPropagation(); moveItemInGrid(id, idx, 1); }; })(item.id, index);
    var bx = document.createElement('button'); bx.className = 'edit-btn edit-btn-delete'; bx.innerHTML = '🗑️';
    bx.onclick = (function(t, id) { return function(e) { e.stopPropagation(); toggleSelect(t, id); }; })(thumb, item.id);
    ec.appendChild(bu); ec.appendChild(bd); ec.appendChild(bx);
    // MD toggle — bottom left, only for NORMAL
    if (galleryType === 'NORMAL') {
      var bm = document.createElement('button');
      bm.className = 'edit-btn edit-btn-md';
      bm.innerHTML = item.thumbUrl ? 'MD' : 'OG';
      bm.title = item.thumbUrl ? 'Using MD thumb — tap to switch to original' : 'Using original — tap for MD thumb';
      bm.onclick = (function(t, i, btn) { return function(e) {
        e.stopPropagation();
        var nowMd = !!i.thumbUrl;
        if (nowMd) {
          i.thumbUrl = null;
          t.classList.remove('is-md');
          btn.innerHTML = 'OG';
        } else {
          i.thumbUrl = i.value.replace(/\.jpg$/i, '.md.jpg');
          t.classList.add('is-md');
          btn.innerHTML = 'MD';
        }
        // Swap displayed image in grid cell
        var media = t.querySelector('img[data-src], img:not([data-src=""])');
        if (media) {
          media.setAttribute('data-src', i.thumbUrl || i.value);
          media.src = 'data:image/gif;base64,R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs=';
          visibilityObserver.unobserve(media);
          visibilityObserver.observe(media);
        }
        Android.toggleMd(i.id, !nowMd);
      }; })(thumb, item, bm);
      ec.appendChild(bm);
    }
    thumb.appendChild(ec);
  }

  // Double-tap copies real URL (item.value, never the .md thumb)
  var lastTap = 0;
  thumb.addEventListener('touchend', function(e) {
    var now = Date.now();
    if (now - lastTap < 300) {
      e.preventDefault();
      e.stopPropagation();
      Android.copyToClipboard(item.value);
      showCopyToast();
    }
    lastTap = now;
  });

  if (galleryType === 'REDGIF') {
    thumb.appendChild(makeOverlay(index));
  } else {
    thumb.onclick = function() { if (!window.editMode) openFullscreen(index); };
  }
  return thumb;
}

function makeOverlay(index) {
  var ov = document.createElement('div');
  ov.className = 'redgif-overlay';
  ov.style.cssText = 'position:absolute;top:0;left:0;width:100%;height:100%;z-index:5;cursor:pointer;';
  ov.onclick = (function(i) { return function(e) { e.stopPropagation(); openFullscreen(i); }; })(index);
  return ov;
}

function buildMedia(item, isFullscreen) {
  var value = item.value;
  var type = galleryType;

  if (type === 'REDGIF') {
    var id = value.includes('redgifs.com') ? value.split('/').pop().split('?')[0] : value;
    id = id.replace(/[^a-zA-Z0-9]/g, '');
    var w = document.createElement('div');
    w.style.cssText = 'width:100%;height:100%;background:#1a1a1a;';
    var f = document.createElement('iframe');
    // Lazy: only set src when scrolled into view via observer
    f.setAttribute('data-src', 'https://www.redgifs.com/ifr/' + id);
    f.style.cssText = 'width:100%;height:100%;border:none;display:block;';
    f.setAttribute('allowfullscreen', '');
    w.appendChild(f);
    return w;
  }

  if (value.match(/\.(mp4|webm)(\?|$)/i)) {
    var v = document.createElement('video');
    // Fullscreen: use src directly for immediate playback with native controls
    if (isFullscreen) {
      v.src = value;
      v.controls = true;
      v.setAttribute('playsinline', '');
      v.style.cssText = 'width:100%;height:100%;object-fit:contain;display:block;';
      return v;
    }
    // Grid view: only CLIPS uses lazy loading (data-src), others load immediately
    if (type === 'CLIPS') {
      v.setAttribute('data-src', value);
      v.muted = true;
      v.loop = true;
      v.autoplay = false;
      v.setAttribute('playsinline', '');
      v.setAttribute('preload', 'none');
    } else {
      v.src = value;
      v.muted = true;
      v.loop = true;
      v.autoplay = false;
      v.setAttribute('playsinline', '');
      v.setAttribute('preload', 'metadata');
    }
    v.style.cssText = 'width:100%;height:100%;object-fit:cover;';
    return v;
  }

  var img = document.createElement('img');
  if (isFullscreen) {
    img.src = value; // always full resolution in fullscreen
  } else {
    var displayUrl = item.thumbUrl || value; // use .md.jpg thumbnail if available
    img.setAttribute('data-src', displayUrl);
    img.src = 'data:image/gif;base64,R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs=';
    img.setAttribute('loading', 'lazy');
    img.setAttribute('decoding', 'async');
  }
  img.style.cssText = isFullscreen
    ? 'width:100%;height:100%;object-fit:contain;display:block;'
    : 'width:100%;height:100%;object-fit:cover;';
  img.onerror = function() { this.style.opacity = '0.3'; };
  return img;
}

// IntersectionObserver: lazy load/unload CLIPS videos, autoplay other videos
var visibilityObserver = new IntersectionObserver(function(entries) {
  entries.forEach(function(entry) {
    var el = entry.target;
    if (el.tagName === 'VIDEO') {
      // CLIPS videos: load when in view, unload when out of view (for memory)
      if (el.getAttribute('data-src')) {
        if (entry.isIntersecting) {
          el.src = el.getAttribute('data-src');
          el.removeAttribute('data-src');
        }
      } else if (galleryType === 'CLIPS') {
        // CLIPS: truly unload when far out of view (free RAM)
        if (!entry.isIntersecting && el.src && el.getAttribute('data-unloaded') !== 'true') {
          var rect = el.getBoundingClientRect();
          var viewportHeight = window.innerHeight;
          // Unload if more than 2 viewport heights away
          if (rect.top < -viewportHeight * 2 || rect.bottom > viewportHeight * 3) {
            el.setAttribute('data-unloaded', 'true');
            el.src = '';
            el.load();
          }
        } else if (entry.isIntersecting && el.getAttribute('data-unloaded') === 'true') {
          // Reload when coming back into view
          var parent = el.closest('.thumb');
          if (parent) {
            var idx = parseInt(parent.getAttribute('data-index'));
            if (items[idx]) {
              el.src = items[idx].value;
              el.removeAttribute('data-unloaded');
            }
          }
        }
      } else {
        // Other videos (NORMAL): autoplay when visible, pause when out of view
        if (entry.isIntersecting) {
          if (el.paused) { el.play().catch(function(){}); }
        } else {
          if (!el.paused) { el.pause(); }
        }
      }
    } else if (entry.isIntersecting && el.getAttribute('data-src')) {
      // Lazy-load: swap data-src → src for iframes and images
      el.src = el.getAttribute('data-src');
      el.removeAttribute('data-src');
      visibilityObserver.unobserve(el); // only need to fire once for images
    }
  });
}, { rootMargin: '200px 0px', threshold: 0.01 });

function observeMedia(container) {
  container.querySelectorAll('video').forEach(function(v) { visibilityObserver.observe(v); });
  container.querySelectorAll('[data-src]').forEach(function(el) { visibilityObserver.observe(el); });
}

function injectResolvedUrl(itemId, url) {
  for (var i = 0; i < items.length; i++) {
    if (items[i].id == itemId) {
      items[i].resolvedUrl = url;
      // Update grid cell if visible
      var cell = document.querySelector('[data-id="' + itemId + '"]');
      if (cell) {
        var firstChild = cell.firstChild;
        if (firstChild && firstChild.tagName === 'VIDEO') break;
        if (firstChild) {
          var newMedia = buildMedia(items[i], false);
          cell.replaceChild(newMedia, firstChild);
          observeMedia(cell);
        }
      }
      // Also update swipe card if swipe mode is open
      injectSwipeResolved(itemId, url);
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
  
  // Render ALL thumbnails at once for all gallery types
  items.forEach(function(item, index) {
    var thumb = buildThumbElement(item, index);
    grid.appendChild(thumb);
    observeMedia(thumb);
  });
}

function openFullscreen(index) {
  var item = items[index];
  var type = galleryType;
  var content = document.getElementById('fullscreenContent');
  var fullscreen = document.getElementById('fullscreen');
  content.innerHTML = '';

  // ── REDGIF ─────────────────────────────────────────────────────────────────
  if (type === 'REDGIF') {
    var currentIdx = index;

    function returnWrapper(idx, w) {
      var t = document.querySelector('[data-index="' + idx + '"]');
      if (!t || !w) return;
      var tapOv = w.querySelector('.tap-overlay');
      if (tapOv) tapOv.remove();
      w.style.cssText = 'width:100%;height:100%;background:#1a1a1a;';
      var fi = w.querySelector('iframe');
      if (fi) fi.style.cssText = 'width:100%;height:100%;border:none;display:block;';
      t.insertBefore(w, t.firstChild);
      if (!t.querySelector('.redgif-overlay')) t.appendChild(makeOverlay(idx));
    }

    function loadIntoFullscreen(idx) {
      var thumb = document.querySelector('[data-index="' + idx + '"]');
      if (thumb) { var ov = thumb.querySelector('.redgif-overlay'); if (ov) ov.remove(); }

      var wrapper = thumb ? thumb.firstChild : null;
      var w;
      if (wrapper && wrapper.querySelector) {
        w = wrapper;
        thumb.removeChild(w);
        w.style.cssText = 'position:absolute;top:0;left:0;width:100%;height:100%;background:#000;';
        var fi = w.querySelector('iframe');
        if (fi) fi.style.cssText = 'width:100%;height:100%;border:none;display:block;';
      } else {
        var id = items[idx].value.includes('redgifs.com') ? items[idx].value.split('/').pop().split('?')[0] : items[idx].value;
        id = id.replace(/[^a-zA-Z0-9]/g, '');
        w = document.createElement('div');
        w.style.cssText = 'position:absolute;top:0;left:0;width:100%;height:100%;background:#000;';
        var f = document.createElement('iframe');
        f.src = 'https://www.redgifs.com/ifr/' + id;
        f.style.cssText = 'width:100%;height:100%;border:none;display:block;';
        f.setAttribute('allowfullscreen', '');
        w.appendChild(f);
      }

      // Tap overlay cycles to next item — moves current wrapper back first
      var tapOv = document.createElement('div');
      tapOv.className = 'tap-overlay';
      tapOv.style.cssText = 'position:absolute;top:0;left:0;width:100%;height:100%;z-index:10;cursor:pointer;';
      tapOv.onclick = function() {
        returnWrapper(currentIdx, content.firstChild);
        currentIdx = (currentIdx + 1) % items.length;
        content.innerHTML = '';
        loadIntoFullscreen(currentIdx);
      };
      w.appendChild(tapOv);

      content.innerHTML = '';
      content.appendChild(w);
    }

    loadIntoFullscreen(currentIdx);

    var xBtn = document.createElement('button');
    xBtn.textContent = '×';
    xBtn.style.cssText = 'position:absolute;top:16px;left:16px;width:48px;height:48px;background:rgba(255,255,255,0.2);border-radius:24px;border:none;color:#fff;font-size:24px;cursor:pointer;z-index:1001;';
    xBtn.onclick = function() {
      returnWrapper(currentIdx, content.firstChild);
      xBtn.remove();
      fullscreen.classList.remove('active');
      content.innerHTML = '';
    };
    fullscreen.appendChild(xBtn);
    fullscreen.classList.add('active');
    return;
  }

  // ── CLIPS ─────────────────────────────────────────────────────────────────
  if (type === 'CLIPS') {
    // CLIPS uses inline HTML5 player - handled below like NORMAL video
  }

  // ── NORMAL image / video ───────────────────────────────────────────────────
  var media = buildMedia(item, true);
  content.appendChild(media);
  fullscreen.classList.add('active');

  // If it's a video in fullscreen, autoplay immediately (intentional user action)
  if (media.tagName === 'VIDEO') {
    media.autoplay = true;
    media.muted = true;
    media.play().catch(function(){});
    observeMedia(content);
    
    // Add random button for CLIPS videos
    if (type === 'CLIPS') {
      var randomBtn = document.createElement('button');
      randomBtn.textContent = '🎲';
      randomBtn.style.cssText = 'position:absolute;top:16px;left:16px;width:48px;height:48px;background:rgba(255,255,255,0.2);border-radius:24px;border:none;color:#fff;font-size:24px;cursor:pointer;z-index:1001;';
      randomBtn.onclick = function(e) {
        e.stopPropagation();
        // Pick random clip different from current
        var currentIdx = items.findIndex(function(i) { return i.id === item.id; });
        var availableIndices = items.map(function(_, i) { return i; }).filter(function(i) { return i !== currentIdx; });
        if (availableIndices.length === 0) return;
        var randomPick = availableIndices[Math.floor(Math.random() * availableIndices.length)];
        var randomItem = items[randomPick];
        // Replace video source with random clip
        var videoUrl = randomItem.value;
        media.src = videoUrl;
        media.play().catch(function(){});
      };
      fullscreen.appendChild(randomBtn);
    }
  }

  if (media.tagName === 'IMG') {
    var rotation = 0, scale = 1, panX = 0, panY = 0;
    var lastDist = 0, isPinching = false;
    var dragStartX = 0, dragStartY = 0, dragStartPanX = 0, dragStartPanY = 0, isDragging = false;

    // ── Bottom buttons ────────────────────────────────────────────────────────
    var btnBar = document.createElement('div');
    btnBar.style.cssText = 'position:absolute;bottom:24px;left:0;width:100%;display:flex;justify-content:center;gap:32px;z-index:1002;pointer-events:none;';

    function makeCtrlBtn(label) {
      var b = document.createElement('button');
      b.textContent = label;
      b.style.cssText = 'width:60px;height:60px;background:rgba(255,255,255,0.15);border:none;color:#fff;font-size:28px;border-radius:30px;cursor:pointer;pointer-events:auto;';
      return b;
    }

    var rotBtn = makeCtrlBtn('↻');
    rotBtn.onclick = function(e) {
      e.stopPropagation();
      rotation = (rotation + 90) % 360;
      scale = 1; panX = 0; panY = 0;
      applyTransform();
    };

    var zoomInBtn = makeCtrlBtn('+');
    zoomInBtn.onclick = function(e) {
      e.stopPropagation();
      scale = Math.min(5, scale * 1.5);
      clampPan();
      applyTransform();
    };

    btnBar.appendChild(rotBtn);
    btnBar.appendChild(zoomInBtn);
    fullscreen.appendChild(btnBar);

    // ── Pan clamping ──────────────────────────────────────────────────────────
    function getMaxPan() {
      var containerW = content.offsetWidth, containerH = content.offsetHeight;
      var sw = media.naturalWidth || media.offsetWidth || containerW;
      var sh = media.naturalHeight || media.offsetHeight || containerH;
      var sideways = rotation === 90 || rotation === 270;
      var fitW = sideways ? containerH : containerW;
      var fitH = sideways ? containerW : containerH;
      var ratio = sw / sh, fitRatio = fitW / fitH;
      var fW = ratio > fitRatio ? fitW : fitH * ratio;
      var fH = ratio > fitRatio ? fitW / ratio : fitH;
      return {
        x: Math.max(0, (fW * scale - containerW) / 2),
        y: Math.max(0, (fH * scale - containerH) / 2)
      };
    }

    function clampPan() {
      var m = getMaxPan();
      panX = Math.max(-m.x, Math.min(m.x, panX));
      panY = Math.max(-m.y, Math.min(m.y, panY));
    }

    function applyTransform() {
      if (rotation === 90 || rotation === 270) {
        media.style.width = '100vh'; media.style.height = '100vw';
      } else {
        media.style.width = '100%'; media.style.height = '100%';
      }
      media.style.objectFit = 'contain';
      media.style.transformOrigin = 'center center';
      // translate THEN rotate so pan axes match screen orientation
      media.style.transform = 'translate(' + panX + 'px,' + panY + 'px) rotate(' + rotation + 'deg) scale(' + scale + ')';
    }

    function touchDist(t) {
      var dx = t[0].clientX - t[1].clientX, dy = t[0].clientY - t[1].clientY;
      return Math.sqrt(dx*dx + dy*dy);
    }

    content.addEventListener('touchstart', function(e) {
      if (e.touches.length === 2) {
        isPinching = true; isDragging = false;
        lastDist = touchDist(e.touches);
      } else if (e.touches.length === 1) {
        isPinching = false;
        dragStartX = e.touches[0].clientX; dragStartY = e.touches[0].clientY;
        dragStartPanX = panX; dragStartPanY = panY;
        isDragging = false;
      }
    }, {passive: true});

    content.addEventListener('touchmove', function(e) {
      e.preventDefault();
      if (e.touches.length === 2 && isPinching) {
        var d = touchDist(e.touches);
        if (lastDist > 0) {
          scale = Math.max(1, Math.min(5, scale * d / lastDist));
          clampPan(); applyTransform();
        }
        lastDist = d;
      } else if (e.touches.length === 1 && !isPinching) {
        var dx = e.touches[0].clientX - dragStartX;
        var dy = e.touches[0].clientY - dragStartY;
        if (Math.abs(dx) > 3 || Math.abs(dy) > 3) isDragging = true;
        if (isDragging) {
          panX = dragStartPanX + dx; panY = dragStartPanY + dy;
          clampPan(); applyTransform();
        }
      }
    }, {passive: false});

    content.addEventListener('touchend', function() {
      lastDist = 0; isPinching = false;
    });

    applyTransform();
  }
}

function closeFullscreen() {
  // Clean up any added button bars or extra buttons
  document.querySelectorAll('#fullscreen div[style*="bottom:24px"]').forEach(function(b){ b.remove(); });
  document.querySelectorAll('#fullscreen button:not(.close-btn):not(.random-btn)').forEach(function(b){ b.remove(); });
  document.getElementById('fullscreen').classList.remove('active');
  document.getElementById('fullscreenContent').innerHTML = '';
  // Reset orientation
  Android.resetOrientation();
}

var selectedIds = new Set();

function toggleSelect(thumb, id) {
  if (thumb.classList.contains('selected')) {
    thumb.classList.remove('selected');
    selectedIds.delete(id);
  } else {
    thumb.classList.add('selected');
    selectedIds.add(id);
  }
  var delBtn = document.getElementById('btn-delete-sel');
  if (delBtn) delBtn.style.display = selectedIds.size > 0 ? 'block' : 'none';
}

function deleteSelected() {
  if (selectedIds.size === 0) return;
  if (!confirm('Delete ' + selectedIds.size + ' item(s)?')) return;
  var ids = Array.from(selectedIds).join(',');
  Android.deleteItems(ids);
  selectedIds.clear();
}

function showCopyToast() {
  var t = document.getElementById('copy-toast');
  if (!t) return;
  t.classList.add('show');
  setTimeout(function() { t.classList.remove('show'); }, 1500);
}

function toggleEditMode() {
  window.editMode = !window.editMode;
  document.body.classList.toggle('edit-mode', window.editMode);
  document.getElementById('toolbar').classList.toggle('edit-mode-active', window.editMode);
  if (!window.editMode) {
    // Clear all selections when exiting edit mode
    selectedIds.clear();
    document.querySelectorAll('.thumb.selected').forEach(function(t) { t.classList.remove('selected'); });
    var delBtn = document.getElementById('btn-delete-sel');
    if (delBtn) delBtn.style.display = 'none';
  }
}

// Swap two grid cells in-place (DOM + items array) then persist to DB.
// Edit mode stays open — no page reload.
function moveItemInGrid(itemId, currentIndex, direction) {
  var newIndex = currentIndex + direction;
  if (newIndex < 0 || newIndex >= items.length) return;

  // Swap in items array
  var tmp = items[currentIndex];
  items[currentIndex] = items[newIndex];
  items[newIndex] = tmp;

  // Swap DOM nodes
  var cells = grid.querySelectorAll('.thumb');
  var cellA = cells[currentIndex];
  var cellB = cells[newIndex];
  if (!cellA || !cellB) return;

  // Re-wire the index attributes
  cellA.setAttribute('data-index', newIndex);
  cellB.setAttribute('data-index', currentIndex);

  // DOM swap using a placeholder
  var placeholder = document.createElement('div');
  grid.insertBefore(placeholder, cellA);
  grid.insertBefore(cellA, cellB);
  grid.insertBefore(cellB, placeholder);
  placeholder.remove();

  // Re-wire onclick handlers for non-REDGIF (REDGIF uses overlay)
  if (galleryType !== 'REDGIF') {
    cellA.onclick = (function(i) { return function() { openFullscreen(i); }; })(newIndex);
    cellB.onclick = (function(i) { return function() { openFullscreen(i); }; })(currentIndex);
  }

  // Persist swap to DB asynchronously — edit mode stays open
  Android.moveItem(itemId, direction);
}

// ── Tinder swipe engine ───────────────────────────────────────────────────────
// Cards are pre-built into a pool so media keeps playing across swipes — no reload.
// Order is a weighted shuffle: item.weight (default 1) biases probability.

var swipeOrder = [];    // shuffled index list into items[]
var swipePos = 0;       // position in swipeOrder
var swipeHistory = [];  // stack of swipePos values for rewind
var swipeMuted = true;

// Pool of 3 card elements: [prev(hidden), current(top), next(back)]
var cardPool = [null, null, null]; // DOM elements
var cardPoolIdx = [null, null, null]; // which swipeOrder index each holds

function weightedShuffle(arr) {
  // Items with weight=0 are burned — never appear in swipe mode
  var weighted = [];
  arr.forEach(function(i) {
    var w = items[i].weight;
    if (w == null) w = 1;
    if (w <= 0) return; // burned — skip
    for (var j = 0; j < w; j++) weighted.push(i);
  });
  for (var i = weighted.length - 1; i > 0; i--) {
    var j = Math.floor(Math.random() * (i + 1));
    var t = weighted[i]; weighted[i] = weighted[j]; weighted[j] = t;
  }
  var seen = {};
  var result = [];
  weighted.forEach(function(i) {
    if (!seen[i]) { seen[i] = true; result.push(i); }
  });
  return result;
}

function buildSwipeMedia(item) {
  var type = galleryType;
  if (type === 'REDGIF') {
    var id = item.value.includes('redgifs.com') ? item.value.split('/').pop().split('?')[0] : item.value;
    id = id.replace(/[^a-zA-Z0-9]/g, '');
    var f = document.createElement('iframe');
    f.src = 'https://www.redgifs.com/ifr/' + id;
    f.style.cssText = 'width:100%;height:100%;border:none;display:block;';
    f.setAttribute('allowfullscreen', '');
    return f;
  }
  var src = item.resolvedUrl || item.value;
  if (src && src.match(/\.(mp4|webm)(\?|$)/i)) {
    var v = document.createElement('video');
    v.src = src; v.autoplay = true; v.muted = true; v.loop = true;
    v.setAttribute('playsinline', '');
    v.style.cssText = 'width:100%;height:100%;object-fit:cover;';
    return v;
  }
  // Always use real full URL in swipe mode (never .md thumb)
  var img = document.createElement('img');
  img.src = item.value;
  img.style.cssText = 'width:100%;height:100%;object-fit:contain;display:block;background:#000;';
  img.onerror = function() { this.style.opacity = '0.2'; };
  // Double-tap opens full-res expand overlay (stays inside swipe mode)
  var lastTapSwipe = 0;
  img.addEventListener('touchend', function(e) {
    var now = Date.now();
    if (now - lastTapSwipe < 300) {
      e.preventDefault(); e.stopPropagation();
      openSwipeExpand(item.value);
    }
    lastTapSwipe = now;
  });
  return img;
}

function buildCardElement(orderPos, isBack) {
  if (orderPos < 0 || orderPos >= swipeOrder.length) return null;
  var item = items[swipeOrder[orderPos]];
  var isLandscape = (galleryType === 'CLIPS');
  var card = document.createElement('div');
  card.className = (isBack ? 'swipe-card-back' : 'swipe-card') + (isLandscape ? ' landscape' : '');
  card.setAttribute('data-order-pos', orderPos);
  var inner = document.createElement('div');
  inner.className = 'card-inner';
  if (isBack) {
    // Lazy placeholder — real media loads when this card is promoted to top
    var ph = document.createElement('div');
    ph.style.cssText = 'width:100%;height:100%;background:#1a1a1a;';
    inner.appendChild(ph);
  } else {
    inner.appendChild(buildSwipeMedia(item));
    var like = document.createElement('div');
    like.className = 'swipe-stamp like'; like.textContent = '👍';
    var nope = document.createElement('div');
    nope.className = 'swipe-stamp nope'; nope.textContent = '👎';
    inner.appendChild(like);
    inner.appendChild(nope);
  }
  card.appendChild(inner);
  if (isBack) {
    card.style.cssText = 'transform: scale(0.93) translateY(14px); z-index: 1; opacity:0.65;';
  } else {
    card.style.zIndex = '2';
  }
  return card;
}

// Load real media into a card that was previously a lazy back-card placeholder
function hydrateCard(card, orderPos) {
  if (!card || orderPos < 0 || orderPos >= swipeOrder.length) return;
  var item = items[swipeOrder[orderPos]];
  var inner = card.querySelector('.card-inner');
  if (!inner) return;
  var ph = inner.firstChild;
  // Replace placeholder with real media
  var media = buildSwipeMedia(item);
  inner.replaceChild(media, ph);
  // Add stamps
  var like = document.createElement('div');
  like.className = 'swipe-stamp like'; like.textContent = '👍';
  var nope = document.createElement('div');
  nope.className = 'swipe-stamp nope'; nope.textContent = '👎';
  inner.appendChild(like);
  inner.appendChild(nope);
}

function updateCounter() {
  var el = document.getElementById('swipe-counter');
  if (el) el.textContent = (swipePos + 1) + ' / ' + swipeOrder.length;
}

function initCardPool() {
  var stack = document.getElementById('card-stack');
  stack.innerHTML = '';
  cardPool = [null, null, null];
  cardPoolIdx = [null, null, null];

  if (swipeOrder.length === 0) {
    stack.innerHTML = '<div style="color:#555;text-align:center;padding:40px;font-size:18px;">No items</div>';
    return;
  }
  if (swipePos >= swipeOrder.length) {
    stack.innerHTML = '<div style="color:#555;text-align:center;padding:40px;font-size:18px;">All done!<br><span style="font-size:14px;margin-top:8px;display:block;">Tap ↩ to rewind</span></div>';
    updateCounter();
    return;
  }

  // Build back card (next)
  if (swipePos + 1 < swipeOrder.length) {
    cardPool[2] = buildCardElement(swipePos + 1, true);
    cardPoolIdx[2] = swipePos + 1;
    stack.appendChild(cardPool[2]);
  }
  // Build top card (current)
  cardPool[1] = buildCardElement(swipePos, false);
  cardPoolIdx[1] = swipePos;
  stack.appendChild(cardPool[1]);
  attachDrag(cardPool[1]);
  updateCounter();
}

// Advance pool after a swipe: promote back→top, build new next, no media reload
function advancePool(dir) {
  var stack = document.getElementById('card-stack');
  swipePos++;

  if (swipePos >= swipeOrder.length) {
    // All done — clear pool
    cardPool = [null, null, null];
    stack.innerHTML = '<div style="color:#555;text-align:center;padding:40px;font-size:18px;">All done!<br><span style="font-size:14px;margin-top:8px;display:block;">Tap ↩ to rewind</span></div>';
    updateCounter();
    return;
  }

  // The old back card becomes the new top card — hydrate it (load real media), swap class, re-attach drag
  var newTop = cardPool[2];
  if (newTop) {
    // Load real media now that this card is about to be the top
    hydrateCard(newTop, swipePos);
    newTop.className = newTop.className.replace('swipe-card-back', 'swipe-card');
    newTop.style.cssText = 'z-index: 2;';
    newTop.style.transition = 'transform 0.3s cubic-bezier(0.175,0.885,0.32,1.275)';
    newTop.style.transform = 'scale(1) translateY(0)';
    newTop.style.opacity = '1';
    // Add stamps
    var inner = newTop.querySelector('.card-inner');
    if (inner && !inner.querySelector('.swipe-stamp')) {
      var like = document.createElement('div');
      like.className = 'swipe-stamp like'; like.textContent = '👍';
      var nope = document.createElement('div');
      nope.className = 'swipe-stamp nope'; nope.textContent = '👎';
      inner.appendChild(like); inner.appendChild(nope);
    }
    cardPool[1] = newTop;
    cardPoolIdx[1] = swipePos;
    // Apply individual mute to new top card for CLIPS
    if (galleryType === 'CLIPS') {
      setTimeout(function() { applyIndividualMuteToCurrentCard(); }, 50);
    }
    setTimeout(function() {
      if (newTop.style) newTop.style.transition = '';
      attachDrag(newTop);
    }, 320);
  } else {
    // No back card was ready — build fresh
    cardPool[1] = buildCardElement(swipePos, false);
    cardPoolIdx[1] = swipePos;
    stack.appendChild(cardPool[1]);
    attachDrag(cardPool[1]);
  }

  // Pre-build next card and add to back of stack
  cardPool[2] = null;
  if (swipePos + 1 < swipeOrder.length) {
    cardPool[2] = buildCardElement(swipePos + 1, true);
    cardPoolIdx[2] = swipePos + 1;
    stack.insertBefore(cardPool[2], cardPool[1]);
  }

  updateCounter();
}

function attachDrag(card) {
  var startX = 0, startY = 0, curX = 0, curY = 0;
  var dragging = false;
  var likeStamp = card.querySelector('.swipe-stamp.like');
  var nopeStamp = card.querySelector('.swipe-stamp.nope');

  function onStart(x, y) {
    startX = x; startY = y; curX = 0; dragging = true;
    card.style.transition = 'none';
  }
  function onMove(x, y) {
    if (!dragging) return;
    curX = x - startX;
    curY = (y - startY) * 0.12;
    var rot = curX * 0.07;
    card.style.transform = 'translate(' + curX + 'px,' + curY + 'px) rotate(' + rot + 'deg)';
    var ratio = Math.min(Math.abs(curX) / 90, 1);
    if (curX > 0) { likeStamp.style.opacity = ratio; nopeStamp.style.opacity = 0; }
    else          { nopeStamp.style.opacity = ratio; likeStamp.style.opacity = 0; }
    var backCard = cardPool[2];
    if (backCard) {
      var s = 0.93 + 0.07 * ratio;
      var ty = 14 - 14 * ratio;
      backCard.style.transform = 'scale(' + s + ') translateY(' + ty + 'px)';
      backCard.style.opacity = 0.65 + 0.35 * ratio;
    }
  }
  function onEnd() {
    if (!dragging) return;
    dragging = false;
    var threshold = window.innerWidth * 0.35;
    if (Math.abs(curX) > threshold) {
      flyOut(curX > 0 ? 'right' : 'left', card);
    } else {
      card.style.transition = 'transform 0.4s cubic-bezier(0.175,0.885,0.32,1.275)';
      card.style.transform = 'translate(0,0) rotate(0deg)';
      likeStamp.style.opacity = 0;
      nopeStamp.style.opacity = 0;
      var backCard = cardPool[2];
      if (backCard) {
        backCard.style.transition = 'transform 0.4s cubic-bezier(0.175,0.885,0.32,1.275), opacity 0.4s';
        backCard.style.transform = 'scale(0.93) translateY(14px)';
        backCard.style.opacity = '0.65';
      }
      setTimeout(function() { card.style.transition = ''; }, 420);
    }
  }

  card.addEventListener('touchstart', function(e) { onStart(e.touches[0].clientX, e.touches[0].clientY); }, {passive:true});
  card.addEventListener('touchmove',  function(e) { e.preventDefault(); onMove(e.touches[0].clientX, e.touches[0].clientY); }, {passive:false});
  card.addEventListener('touchend',   function() { onEnd(); });
  card.addEventListener('mousedown',  function(e) { onStart(e.clientX, e.clientY); });
  document.addEventListener('mousemove', function(e) { if (dragging) onMove(e.clientX, e.clientY); });
  document.addEventListener('mouseup',   function() { onEnd(); });
}

function spawnParticles(dir) {
  var isLike = (dir === 'right');
  var emoji = isLike ? '♥' : '✕';
  var color = isLike ? '#4caf50' : '#f44336';
  var cx = window.innerWidth * (isLike ? 0.75 : 0.25);
  var cy = window.innerHeight * 0.45;
  var count = 14;
  for (var i = 0; i < count; i++) {
    (function() {
      var p = document.createElement('div');
      p.className = 'particle';
      p.textContent = emoji;
      p.style.color = color;
      p.style.left = cx + 'px';
      p.style.top  = cy + 'px';
      var angle = (Math.random() * Math.PI * 2);
      var dist  = 80 + Math.random() * 140;
      var tx = Math.cos(angle) * dist;
      var ty = Math.sin(angle) * dist - 60;
      p.style.setProperty('--tx', tx + 'px');
      p.style.setProperty('--ty', ty + 'px');
      p.style.animationDelay = (Math.random() * 0.12) + 's';
      p.style.fontSize = (22 + Math.random() * 18) + 'px';
      document.body.appendChild(p);
      setTimeout(function() { p.remove(); }, 900);
    })();
  }
}

function flyOut(dir, card) {
  if (!card) card = cardPool[1];
  if (!card) return;
  var tx = dir === 'right' ? window.innerWidth * 1.6 : -window.innerWidth * 1.6;
  var rot = dir === 'right' ? 32 : -32;
  card.style.transition = 'transform 0.38s cubic-bezier(0.55,0,1,0.45), opacity 0.38s';
  card.style.transform = 'translate(' + tx + 'px,0) rotate(' + rot + 'deg)';
  card.style.opacity = '0';
  var backCard = cardPool[2];
  if (backCard) {
    backCard.style.transition = 'transform 0.38s cubic-bezier(0.175,0.885,0.32,1.275), opacity 0.38s';
    backCard.style.transform = 'scale(1) translateY(0)';
    backCard.style.opacity = '1';
  }
  spawnParticles(dir);
  swipeHistory.push(swipePos);
  setTimeout(function() {
    if (card.parentNode) card.parentNode.removeChild(card);
    advancePool(dir);
  }, 370);
}

function openSwipeExpand(url) {
  var img = document.getElementById('swipe-expand-img');
  if (img) img.src = url;
  document.getElementById('swipe-expand').classList.add('active');
}

function closeSwipeExpand() {
  document.getElementById('swipe-expand').classList.remove('active');
  var img = document.getElementById('swipe-expand-img');
  if (img) img.src = '';
}

function deleteCurrentSwipeItem() {
  if (swipePos >= swipeOrder.length) return;
  var itemIdx = swipeOrder[swipePos];
  var item = items[itemIdx];
  if (!confirm('Delete this item?')) return;
  Android.deleteItemSilent(item.id);
  // Remove from items array and swipeOrder
  swipeOrder.splice(swipePos, 1);
  items.splice(itemIdx, 1);
  // Fix remaining swipeOrder indices that pointed past the removed item
  for (var i = 0; i < swipeOrder.length; i++) {
    if (swipeOrder[i] > itemIdx) swipeOrder[i]--;
  }
  if (swipePos >= swipeOrder.length) swipePos = Math.max(0, swipeOrder.length - 1);
  initCardPool();
}

function programmaticSwipe(dir) { flyOut(dir, cardPool[1]); }

function burnCurrent() {
  if (swipePos >= swipeOrder.length) return;
  var itemIdx = swipeOrder[swipePos];
  items[itemIdx].weight = 0;           // mark locally so shuffle skips it
  Android.burnItem(items[itemIdx].id); // persist weight=0 to DB
  flyOut('left', cardPool[1]);         // fly out like a nope
}

function toggleSwipeMute() {
  swipeMuted = !swipeMuted;
  document.querySelectorAll('#card-stack video').forEach(function(v) {
    v.muted = swipeMuted;
    if (!swipeMuted) v.volume = defaultVolume / 100;
  });
}

function toggleSwipeFlip() {
  var sv = document.getElementById('swipe-view');
  sv.classList.toggle('flipped');
  sv.classList.toggle('clips-landscape');
}

var individualMuteEnabled = false;

function toggleIndividualMute() {
  individualMuteEnabled = !individualMuteEnabled;
  var btn = document.getElementById('individualMuteBtn');
  if (btn) btn.textContent = individualMuteEnabled ? '🔊' : '🔇';
  
  // Apply to current visible card immediately
  applyIndividualMuteToCurrentCard();
}

function applyIndividualMuteToCurrentCard() {
  // Apply to the top card in the stack (cardPool[1])
  var topCard = cardPool[1];
  if (topCard) {
    var video = topCard.querySelector('video');
    if (video) {
      video.muted = !individualMuteEnabled;
      if (!individualMuteEnabled) video.volume = defaultVolume / 100;
    }
  }
  // Also apply to next card if exists
  var nextCard = cardPool[2];
  if (nextCard) {
    var video = nextCard.querySelector('video');
    if (video) {
      video.muted = !individualMuteEnabled;
      if (!individualMuteEnabled) video.volume = defaultVolume / 100;
    }
  }
}

function enterSwipeMode() {
  // Just pause grid videos - don't clear grid
  document.querySelectorAll('#grid video').forEach(function(v) { v.pause(); });
  
  // Weighted shuffle on every entry so order is fresh-random each time
  swipeOrder = weightedShuffle(items.map(function(_, i) { return i; }));
  swipePos = 0;
  swipeHistory = [];
  swipeMuted = true;
  cardPool = [null, null, null];
  cardPoolIdx = [null, null, null];
  var sv = document.getElementById('swipe-view');
  sv.classList.add('active');
  sv.classList.remove('clips-landscape');
  
  // Show individual mute button for CLIPS
  var imBtn = document.getElementById('individualMuteBtn');
  if (imBtn && galleryType === 'CLIPS') {
    imBtn.style.display = 'block';
    imBtn.textContent = '🔇';
  }
  
  initCardPool();
  
  // Apply individual mute setting to current card
  if (galleryType === 'CLIPS' && window.individualMuteEnabled) {
    applyIndividualMuteToCurrentCard();
  }
}

function exitSwipeMode() {
  document.querySelectorAll('#card-stack video').forEach(function(v) { v.pause(); v.muted = true; });
  var sv = document.getElementById('swipe-view');
  sv.classList.remove('active');
  sv.classList.remove('clips-landscape');
  sv.classList.remove('flipped');
  document.getElementById('card-stack').innerHTML = '';
  cardPool = [null, null, null];
  
  // Re-observe videos after exiting Tinder
  if (typeof visibilityObserver !== 'undefined') {
    document.querySelectorAll('#grid video, #grid iframe[data-src]').forEach(function(el) {
      visibilityObserver.unobserve(el); visibilityObserver.observe(el);});
  }
}

// inject resolved URL into swipe card if it's the active one
function injectSwipeResolved(itemId, url) {
  // Update cardPool[1] (current top) if it matches
  [1, 2].forEach(function(slot) {
    var card = cardPool[slot];
    var pos  = cardPoolIdx[slot];
    if (!card || pos == null) return;
    if (items[swipeOrder[pos]] && items[swipeOrder[pos]].id == itemId) {
      var inner = card.querySelector('.card-inner');
      var first = inner ? inner.firstChild : null;
      if (first && first.tagName !== 'VIDEO') {
        items[swipeOrder[pos]].resolvedUrl = url;
        var v = document.createElement('video');
        v.src = url; v.autoplay = (slot === 1); v.muted = swipeMuted; v.loop = true;
        v.volume = defaultVolume / 100;
        v.setAttribute('playsinline', '');
        v.style.cssText = 'width:100%;height:100%;object-fit:cover;';
        inner.replaceChild(v, first);
      }
    }
  });
}

var tinderIndex = -1;
function showRandomItem() { enterSwipeMode(); }

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
        fun deleteItems(ids: String) {
            lifecycleScope.launch {
                val idList = ids.split(",").mapNotNull { it.trim().toLongOrNull() }
                withContext(Dispatchers.IO) {
                    idList.forEach { VaultsApp.instance.db.galleryItemDao().deleteById(it) }
                }
                loadGalleryItems()
            }
        }

        @JavascriptInterface
        fun copyToClipboard(text: String) {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("url", text))
        }

        @JavascriptInterface
        fun moveItem(itemId: Long, direction: Int) {
            lifecycleScope.launch(Dispatchers.IO) {
                val items = VaultsApp.instance.db.galleryItemDao().getItemsOnce(galleryId)
                val currentItem = items.find { it.id == itemId } ?: return@launch
                val currentIndex = items.indexOf(currentItem)
                val newIndex = currentIndex + direction
                if (newIndex !in items.indices) return@launch
                val otherItem = items[newIndex]
                val tempSort = currentItem.sortOrder
                VaultsApp.instance.db.galleryItemDao().updateSortOrder(itemId, otherItem.sortOrder)
                VaultsApp.instance.db.galleryItemDao().updateSortOrder(otherItem.id, tempSort)
                // No UI reload — JS already swapped the DOM nodes in moveItemInGrid()
            }
        }

        @JavascriptInterface
        fun exportItems() {
            lifecycleScope.launch {
                val items = withContext(Dispatchers.IO) {
                    VaultsApp.instance.db.galleryItemDao().getItemsOnce(galleryId)
                }
                val gallery = withContext(Dispatchers.IO) {
                    VaultsApp.instance.db.galleryDao().getGalleryById(galleryId)
                }
                val text = items.joinToString("\n") { it.value }
                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, gallery?.name ?: "Vaults Export")
                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                }
                context.startActivity(android.content.Intent.createChooser(shareIntent, "Export ${items.size} items"))
            }
        }

        @JavascriptInterface
        fun getClipsGalleries(): String {
            val galleries = runBlocking {
                withContext(Dispatchers.IO) {
                    val allGalleries = mutableListOf<com.vaults.app.db.Gallery>()
                    val toVisit = ArrayDeque<Long?>()
                    toVisit.add(null)
                    while (toVisit.isNotEmpty()) {
                        val parentId = toVisit.removeFirst()
                        val children = if (parentId == null)
                            VaultsApp.instance.db.galleryDao().getRootGalleriesOnce()
                        else
                            VaultsApp.instance.db.galleryDao().getChildGalleriesOnce(parentId)
                        children.forEach { g ->
                            if (g.type == com.vaults.app.db.GalleryType.CLIPS) allGalleries.add(g)
                            if (g.type == com.vaults.app.db.GalleryType.FOLDER) toVisit.add(g.id)
                        }
                    }
                    allGalleries
                }
            }
            val arr = org.json.JSONArray()
            galleries.forEach { g ->
                arr.put(org.json.JSONObject().apply {
                    put("id", g.id)
                    put("name", g.name)
                })
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun addItemToGallery(targetGalleryId: Long, value: String) {
            lifecycleScope.launch {
                val existing = withContext(Dispatchers.IO) {
                    VaultsApp.instance.db.galleryItemDao().getExistingValues(targetGalleryId).toSet()
                }
                if (value in existing) return@launch
                val currentMax = withContext(Dispatchers.IO) {
                    VaultsApp.instance.db.galleryItemDao().getItemsOnce(targetGalleryId)
                        .maxOfOrNull { it.sortOrder } ?: -1
                }
                withContext(Dispatchers.IO) {
                    VaultsApp.instance.db.galleryItemDao().insert(
                        GalleryItem(galleryId = targetGalleryId, value = value, sortOrder = currentMax + 1)
                    )
                }
            }
        }

        @JavascriptInterface
        fun deleteItemSilent(itemId: Long) {
            // Delete from DB without reloading the gallery — swipe mode handles UI itself
            lifecycleScope.launch(Dispatchers.IO) {
                VaultsApp.instance.db.galleryItemDao().deleteById(itemId)
            }
        }

        @JavascriptInterface
        fun toggleMd(itemId: Long, useMd: Boolean) {
            lifecycleScope.launch(Dispatchers.IO) {
                VaultsApp.instance.db.galleryItemDao().updateUseMd(itemId, useMd)
            }
        }

        @JavascriptInterface
        fun saveColumnCount(count: Int) {
            lifecycleScope.launch(Dispatchers.IO) {
                VaultsApp.instance.db.galleryDao().updateColumnCount(galleryId, count)
            }
        }

        @JavascriptInterface
        fun burnItem(itemId: Long) {
            lifecycleScope.launch(Dispatchers.IO) {
                VaultsApp.instance.db.galleryItemDao().updateWeight(itemId, 0)
            }
        }

        @JavascriptInterface
        fun goBack() {
            finish()
        }

        @JavascriptInterface
        fun openInAppUrl(url: String, landscape: Boolean = false) {
            val intent = android.content.Intent(context, InAppBrowserActivity::class.java)
            intent.putExtra("url", url)
            intent.putExtra("landscape", landscape)
            context.startActivity(intent)
        }

        @JavascriptInterface
        fun requestLandscape() {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        @JavascriptInterface
        fun resetOrientation() {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        @JavascriptInterface
        fun showAddDialog() {
            runOnUiThread {
                val container = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(48, 24, 48, 8)
                }

                val input = android.widget.EditText(context).apply {
                    hint = "Enter URLs (comma or newline separated)"
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(android.graphics.Color.parseColor("#2a2a2a"))
                    setPadding(16, 16, 16, 16)
                    minLines = 3
                }
                container.addView(input)

                fun makeRow(label: String, default: Boolean): android.widget.Switch {
                    val row = android.widget.LinearLayout(context).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(0, 16, 0, 0)
                    }
                    val tv = android.widget.TextView(context).apply {
                        text = label
                        setTextColor(android.graphics.Color.WHITE)
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val sw = android.widget.Switch(context).apply { isChecked = default }
                    row.addView(tv); row.addView(sw)
                    container.addView(row)
                    return sw
                }

                val topSwitch = makeRow("Add to top", false)
                // MD toggle only makes sense for NORMAL image galleries
                val mdSwitch = if (galleryType == GalleryType.NORMAL) makeRow("MD thumbs (.md.jpg)", false) else null

                android.app.AlertDialog.Builder(context)
                    .setTitle("Add Media")
                    .setView(container)
                    .setPositiveButton("Add") { _, _ ->
                        val text = input.text.toString()
                        if (text.isNotBlank()) {
                            addItems(text, topSwitch.isChecked, mdSwitch?.isChecked ?: false)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        private fun addItems(text: String, addToTop: Boolean = false, useMd: Boolean = false) {
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
                if (newValues.isEmpty()) { loadGalleryItems(); return@launch }

                if (addToTop) {
                    withContext(Dispatchers.IO) {
                        VaultsApp.instance.db.galleryItemDao().shiftAllSortOrders(galleryId, newValues.size)
                    }
                    val newItems = newValues.mapIndexed { index, value ->
                        GalleryItem(galleryId = galleryId, value = value, sortOrder = index, useMd = useMd)
                    }
                    withContext(Dispatchers.IO) {
                        VaultsApp.instance.db.galleryItemDao().insertAll(newItems)
                    }
                } else {
                    val currentMax = withContext(Dispatchers.IO) {
                        VaultsApp.instance.db.galleryItemDao().getItemsOnce(galleryId)
                            .maxOfOrNull { it.sortOrder } ?: -1
                    }
                    val newItems = newValues.mapIndexed { index, value ->
                        GalleryItem(galleryId = galleryId, value = value, sortOrder = currentMax + index + 1, useMd = useMd)
                    }
                    withContext(Dispatchers.IO) {
                        VaultsApp.instance.db.galleryItemDao().insertAll(newItems)
                    }
                }

                loadGalleryItems()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-observe all media so IntersectionObserver fires again for currently-visible videos
        binding.webView.evaluateJavascript(
            "if(typeof visibilityObserver!=='undefined'){" +
            "document.querySelectorAll('#grid video, #grid iframe[data-src]').forEach(function(el){" +
            "visibilityObserver.unobserve(el); visibilityObserver.observe(el);});}", null
        )
    }

    // Auto-mute all videos when leaving the gallery (back, home, switching apps, opening fullscreen browser)
    // This prevents audio bleeding into other activities or the home screen.
    override fun onPause() {
        super.onPause()
        binding.webView.evaluateJavascript(
            "document.querySelectorAll('#grid video').forEach(function(v){v.muted=true;v.pause();});" +
            "isMuted=true;" +
            "var btn=document.getElementById('unmuteBtn');if(btn)btn.textContent='🔇';",
            null
        )
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