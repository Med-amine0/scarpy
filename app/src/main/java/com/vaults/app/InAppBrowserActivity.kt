package com.vaults.app

import android.annotation.SuppressLint
import android.graphics.Color
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class InAppBrowserActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWebviewGalleryBinding
    private var currentGifId: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        binding = ActivityWebviewGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra("url") ?: run { finish(); return }
        val forceLandscape = intent.getBooleanExtra("landscape", false)
        if (forceLandscape) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                if (loadedUrl != null && loadedUrl.contains("pornhub.com/gif/")) {
                    currentGifId = loadedUrl.substringAfterLast("/gif/").substringBefore("?").substringBefore("#")
                    injectAddButton()
                }
            }
        }
        binding.webView.addJavascriptInterface(BrowserBridge(), "AndroidBrowser")

        // X button overlay to close
        val closeBtn = android.widget.Button(this).apply {
            text = "×"
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#88000000"))
            setPadding(0, 0, 0, 0)
        }
        val lp = android.widget.FrameLayout.LayoutParams(120, 80).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            topMargin = 24
        }
        (binding.root as? android.widget.FrameLayout)?.addView(closeBtn, lp)
            ?: run {
                val frame = android.widget.FrameLayout(this)
                frame.addView(binding.root)
                frame.addView(closeBtn, lp)
                setContentView(frame)
            }
        closeBtn.setOnClickListener { finish() }

        // Raw video URL (PH resolved mp4) — wrap in muted HTML player
        if (url.contains(".phncdn.com") || url.contains("cdn-fck.com") ||
            url.matches(Regex(".*\\.(mp4|webm)(\\?.*)?$", RegexOption.IGNORE_CASE))) {
            val html = """<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>*{margin:0;padding:0;box-sizing:border-box}body{background:#000;display:flex;flex-direction:column;height:100vh}video{width:100%;flex:1;object-fit:contain;background:#000}.ctrl{display:flex;justify-content:center;padding:12px;background:#111}button{background:#ff69b4;border:none;color:#fff;padding:10px 24px;border-radius:20px;font-size:16px;cursor:pointer}</style>
</head><body>
<video id="v" src="$url" autoplay muted loop playsinline></video>
<div class="ctrl"><button onclick="var v=document.getElementById('v');v.muted=!v.muted;this.textContent=v.muted?'🔇 Unmute':'🔊 Mute'">🔇 Unmute</button></div>
</body></html>""".trimIndent()
            binding.webView.loadDataWithBaseURL("https://app.vaults.local", html, "text/html", "UTF-8", null)
        } else {
            binding.webView.loadUrl(url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun injectAddButton() {
        val galleries = runBlocking {
            withContext(Dispatchers.IO) {
                VaultsApp.instance.db.galleryDao().getRootGalleriesOnce()
                    .filter { it.type == GalleryType.PORNHUB }
            }
        }
        if (galleries.isEmpty()) return
        val galJson = galleries.joinToString(",") { """{"id":${it.id},"name":"${it.name.replace("\"","\\\"")}"}""" }
        val js = """(function(){
  if(document.getElementById('vaults-add-btn'))return;
  var btn=document.createElement('button');
  btn.id='vaults-add-btn';btn.textContent='+';
  btn.style.cssText='position:fixed;top:12px;right:12px;z-index:99999;width:44px;height:44px;background:#ff69b4;color:#fff;border:none;border-radius:22px;font-size:24px;cursor:pointer;box-shadow:0 2px 8px rgba(0,0,0,.5);';
  var gals=[$galJson];
  btn.onclick=function(){
    var ov=document.createElement('div');
    ov.style.cssText='position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,.7);z-index:100000;display:flex;align-items:center;justify-content:center;';
    var box=document.createElement('div');
    box.style.cssText='background:#1e1e1e;border-radius:16px;padding:16px;min-width:240px;max-width:90%;';
    var t=document.createElement('p');t.textContent='Add to gallery:';
    t.style.cssText='color:#ff69b4;font-size:16px;margin-bottom:12px;font-weight:bold;';
    box.appendChild(t);
    gals.forEach(function(g){
      var r=document.createElement('button');r.textContent=g.name;
      r.style.cssText='display:block;width:100%;background:#2a2a2a;border:none;color:#fff;padding:12px;border-radius:8px;margin-bottom:8px;font-size:15px;cursor:pointer;text-align:left;';
      r.onclick=function(){AndroidBrowser.addToGallery(g.id,AndroidBrowser.getCurrentGifId());document.body.removeChild(ov);btn.textContent='✓';setTimeout(function(){btn.textContent='+';},1500);};
      box.appendChild(r);
    });
    var c=document.createElement('button');c.textContent='Cancel';
    c.style.cssText='display:block;width:100%;background:transparent;border:1px solid #444;color:#888;padding:10px;border-radius:8px;font-size:14px;cursor:pointer;';
    c.onclick=function(){document.body.removeChild(ov);};
    box.appendChild(c);ov.appendChild(box);
    ov.onclick=function(e){if(e.target===ov)document.body.removeChild(ov);};
    document.body.appendChild(ov);
  };
  document.body.appendChild(btn);
})();"""
        binding.webView.evaluateJavascript(js, null)
    }

    inner class BrowserBridge {
        @JavascriptInterface
        fun getCurrentGifId(): String = currentGifId ?: ""

        @JavascriptInterface
        fun addToGallery(targetGalleryId: Long, gifId: String) {
            if (gifId.isBlank()) return
            lifecycleScope.launch {
                val existing = withContext(Dispatchers.IO) {
                    VaultsApp.instance.db.galleryItemDao().getExistingValues(targetGalleryId).toSet()
                }
                if (gifId in existing) return@launch
                val max = withContext(Dispatchers.IO) {
                    VaultsApp.instance.db.galleryItemDao().getItemsOnce(targetGalleryId).maxOfOrNull { it.sortOrder } ?: -1
                }
                withContext(Dispatchers.IO) {
                    VaultsApp.instance.db.galleryItemDao().insert(
                        GalleryItem(galleryId = targetGalleryId, value = gifId, sortOrder = max + 1)
                    )
                }
            }
        }
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
    }
}
