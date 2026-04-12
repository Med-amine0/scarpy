package com.vaults.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.vaults.app.databinding.ActivityWebviewGalleryBinding

class InAppBrowserActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWebviewGalleryBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra("url") ?: run { finish(); return }

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.webViewClient = object : WebViewClient() {}

        binding.webView.loadUrl(url)
    }

    override fun onBackPressed() {
        finish()
    }
}
