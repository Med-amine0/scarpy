package com.vaults.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.vaults.app.R
import com.vaults.app.databinding.ActivityPlayerBinding
import com.vaults.app.db.GalleryType

class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var currentRotation = 0
    private var isPlaying = false
    
    private val url: String by lazy { intent.getStringExtra("url") ?: "" }
    private val isEmbed: Boolean by lazy { intent.getBooleanExtra("is_embed", false) }
    private val typeName: String by lazy { intent.getStringExtra("type") ?: GalleryType.NORMAL.name }
    private val galleryType: GalleryType by lazy { GalleryType.valueOf(typeName) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUI()
        setupCloseButton()
        setupTapToRotate()
        setupMedia()
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }

    private fun setupCloseButton() {
        binding.btnClose.setOnClickListener {
            finish()
        }
    }

    private fun setupTapToRotate() {
        binding.mediaContainer.setOnClickListener {
            currentRotation = (currentRotation + 90) % 360
            updateRotation()
        }
    }

    private fun updateRotation() {
        binding.mediaContainer.rotation = currentRotation.toFloat()
        
        val targetWidth: Int
        val targetHeight: Int
        
        when (currentRotation) {
            90, 270 -> {
                binding.mediaContainer.post {
                    binding.mediaContainer.layoutParams = binding.mediaContainer.layoutParams.apply {
                        if (this is FrameLayout.LayoutParams) {
                            width = binding.root.height
                            height = binding.root.width
                        }
                    }
                }
            }
        }
        
        fitToScreen()
    }

    private fun fitToScreen() {
        binding.mediaContainer.post {
            val parent = binding.root
            val container = binding.mediaContainer
            
            when (currentRotation) {
                0, 180 -> {
                    val scale = minOf(
                        parent.width.toFloat() / (container.width.takeIf { it > 0 } ?: 1),
                        parent.height.toFloat() / (container.height.takeIf { it > 0 } ?: 1)
                    )
                    container.scaleX = scale
                    container.scaleY = scale
                }
                90, 270 -> {
                    val scale = minOf(
                        parent.width.toFloat() / (container.height.takeIf { it > 0 } ?: 1),
                        parent.height.toFloat() / (container.width.takeIf { it > 0 } ?: 1)
                    )
                    container.scaleX = scale
                    container.scaleY = scale
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupMedia() {
        val isVideo = url.endsWith(".mp4") || url.endsWith(".webm") || url.contains("media") || galleryType == GalleryType.PORNHUB
        val isImage = !isVideo && !isEmbed

        when {
            isEmbed -> setupWebView()
            isVideo -> setupExoPlayer()
            isImage -> setupImage()
        }
    }

    private fun setupWebView() {
        binding.webView.visibility = View.VISIBLE
        binding.playerView.visibility = View.GONE
        binding.imageView.visibility = View.GONE

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
        }

        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }
        }

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <style>
                    * { margin: 0; padding: 0; }
                    html, body { width: 100%; height: 100%; background: #000; overflow: hidden; }
                    iframe { width: 100%; height: 100%; border: none; }
                </style>
            </head>
            <body>
                <iframe src="$url" allowfullscreen></iframe>
            </body>
            </html>
        """.trimIndent()

        binding.webView.loadDataWithBaseURL(url, html, "text/html", "UTF-8", null)
    }

    private fun setupExoPlayer() {
        binding.playerView.visibility = View.VISIBLE
        binding.webView.visibility = View.GONE
        binding.imageView.visibility = View.GONE

        player = ExoPlayer.Builder(this).build().apply {
            val mediaItem = MediaItem.fromUri(url)
            setMediaItem(mediaItem)
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
            prepare()
        }

        binding.playerView.player = player
        binding.playerView.useController = false
        
        binding.playerView.post { fitToScreen() }
    }

    private fun setupImage() {
        binding.imageView.visibility = View.VISIBLE
        binding.playerView.visibility = View.GONE
        binding.webView.visibility = View.GONE

        Glide.with(this)
            .load(url)
            .into(binding.imageView)

        binding.imageView.post { fitToScreen() }
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}