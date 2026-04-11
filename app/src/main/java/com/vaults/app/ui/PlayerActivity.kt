package com.vaults.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.vaults.app.databinding.ActivityPlayerBinding
import com.vaults.app.db.GalleryType
import kotlin.math.min

class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var currentRotation = 0
    private var videoWidth = 1080
    private var videoHeight = 1920
    
    private val url: String by lazy { intent.getStringExtra("url") ?: "" }
    private val isEmbed: Boolean by lazy { intent.getBooleanExtra("is_embed", false) }
    private val typeName: String by lazy { intent.getStringExtra("type") ?: GalleryType.NORMAL.name }
    private val galleryType: GalleryType by lazy { GalleryType.valueOf(typeName) }

    private var currentMediaView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFullscreen()
        setupCloseButton()
        setupTapToRotate()
        setupMedia()
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }

    private fun setupCloseButton() {
        binding.btnClose.setOnClickListener { finish() }
    }

    private fun setupTapToRotate() {
        binding.root.setOnClickListener {
            currentRotation = (currentRotation + 90) % 360
            applyRotation()
        }
    }

    private fun applyRotation() {
        val view = currentMediaView ?: return
        val parent = binding.root
        
        view.rotation = currentRotation.toFloat()
        val isRotated = currentRotation == 90 || currentRotation == 270
        
        view.post {
            val parentW = parent.width
            val parentH = parent.height
            
            val mediaW = videoWidth.toFloat()
            val mediaH = videoHeight.toFloat()
            
            val targetW: Int
            val targetH: Int
            
            if (isRotated) {
                val aspectRatio = mediaW / mediaH
                targetH = parentH
                targetW = min(parentW, (parentH * aspectRatio).toInt())
            } else {
                val aspectRatio = mediaW / mediaH
                targetW = parentW
                targetH = min(parentH, (parentW / aspectRatio).toInt())
            }
            
            view.layoutParams.width = targetW
            view.layoutParams.height = targetH
            view.requestLayout()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupMedia() {
        when (galleryType) {
            GalleryType.PORNHUB -> setupPornhub()
            GalleryType.REDGIF -> setupRedgif()
            GalleryType.NORMAL -> setupNormal()
            GalleryType.FOLDER -> setupNormal()
        }
    }

    private fun setupPornhub() {
        if (url.startsWith("http")) {
            setupExoPlayer(url)
        } else {
            setupIframe("https://www.pornhub.com/embedgif/$url")
        }
    }

    private fun setupRedgif() {
        val id = when {
            url.contains("redgifs.com") -> url.substringAfterLast("/").substringBefore("?").take(12)
            else -> url.take(12)
        }
        setupIframe("https://www.redgifs.com/ifr/$id")
    }

    private fun setupNormal() {
        val isVideo = url.endsWith(".mp4") || url.endsWith(".webm") || url.contains(".mp4") || url.contains(".webm")
        if (isVideo) {
            setupExoPlayer(url)
        } else {
            setupImage(url)
        }
    }

    private fun setupIframe(src: String) {
        binding.webContainer.visibility = View.VISIBLE
        binding.playerContainer.visibility = View.GONE
        binding.imageContainer.visibility = View.GONE
        currentMediaView = binding.webContainer

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
        }

        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.webViewClient = object : WebViewClient() {}

        videoWidth = 1080
        videoHeight = 1920

        val html = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\"><style>* { margin: 0; padding: 0; }html, body { width: 100%; height: 100%; background: #000; overflow: hidden; }iframe { width: 100%; height: 100%; border: none; }</style></head><body><iframe src=\"$src\" frameborder=\"0\" scrolling=\"no\" allowfullscreen></iframe></body></html>"

        binding.webView.loadDataWithBaseURL(src, html, "text/html", "UTF-8", null)
        applyRotation()
    }

    private fun setupExoPlayer(mediaUrl: String) {
        binding.playerContainer.visibility = View.VISIBLE
        binding.webContainer.visibility = View.GONE
        binding.imageContainer.visibility = View.GONE
        currentMediaView = binding.playerContainer

        player = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(mediaUrl))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
            prepare()
            
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                    videoWidth = size.width
                    videoHeight = size.height
                    applyRotation()
                }
            })
        }

        binding.playerView.player = player
        binding.playerView.useController = false
        applyRotation()
    }

    private fun setupImage(imageUrl: String) {
        binding.imageContainer.visibility = View.VISIBLE
        binding.playerContainer.visibility = View.GONE
        binding.webContainer.visibility = View.GONE
        currentMediaView = binding.imageContainer

        Glide.with(this)
            .load(imageUrl)
            .into(binding.imageView)

        applyRotation()
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