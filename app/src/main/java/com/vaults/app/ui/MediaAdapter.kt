package com.vaults.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vaults.app.R
import com.vaults.app.db.GalleryType
import com.vaults.app.vm.MediaItemState

class MediaAdapter(
    private val galleryType: GalleryType,
    private val onItemClick: (MediaItemState) -> Unit,
    private val onItemDelete: (MediaItemState) -> Unit
) : ListAdapter<MediaItemState, MediaAdapter.ViewHolder>(DiffCallback()) {

    private val playerPool = mutableListOf<ExoPlayer>()
    private val activePlayers = mutableMapOf<Int, ExoPlayer>()
    private var context: Context? = null

    fun initPool(ctx: Context) {
        if (context == null) {
            context = ctx
            repeat(8) {
                val player = ExoPlayer.Builder(ctx).build().apply {
                    repeatMode = Player.REPEAT_MODE_ONE
                    volume = 0f
                    playWhenReady = true
                }
                playerPool.add(player)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        if (context == null) initPool(parent.context)
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.releasePlayer()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.thumbnailView)
        private val playerView: androidx.media3.ui.PlayerView = itemView.findViewById(R.id.playerView)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        private val playIcon: ImageView = itemView.findViewById(R.id.playIcon)
        private val errorIcon: ImageView = itemView.findViewById(R.id.errorIcon)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

        private var currentPosition: Int = -1
        private var currentPlayer: ExoPlayer? = null

        fun bind(item: MediaItemState, position: Int) {
            currentPosition = position

            when {
                item.isLoading -> showLoading()
                item.error != null -> showError()
                else -> showMedia(item)
            }

            btnDelete.visibility = View.GONE
            itemView.setOnClickListener { onItemClick(item) }
            itemView.setOnLongClickListener {
                btnDelete.visibility = View.VISIBLE
                btnDelete.setOnClickListener { onItemDelete(item) }
                true
            }
        }

        private fun showLoading() {
            progressBar.visibility = View.VISIBLE
            imageView.visibility = View.GONE
            playerView.visibility = View.GONE
            playIcon.visibility = View.GONE
            errorIcon.visibility = View.GONE
            releasePlayer()
        }

        private fun showError() {
            progressBar.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            playerView.visibility = View.GONE
            playIcon.visibility = View.GONE
            errorIcon.visibility = View.VISIBLE
            releasePlayer()
        }

        private fun showMedia(item: MediaItemState) {
            val videoUrl = item.url
            val embedUrl = item.embedUrl

            when {
                item.isVideo && videoUrl != null -> showVideo(videoUrl)
                embedUrl != null -> showEmbed(embedUrl)
                else -> showImage(item.url ?: item.value)
            }
        }

        private fun showVideo(url: String) {
            progressBar.visibility = View.GONE
            imageView.visibility = View.GONE
            playerView.visibility = View.VISIBLE
            playIcon.visibility = View.GONE
            errorIcon.visibility = View.GONE

            releasePlayer()

            if (playerPool.isNotEmpty()) {
                currentPlayer = playerPool.removeAt(0)
                activePlayers[currentPosition] = currentPlayer!!

                currentPlayer!!.setMediaItem(MediaItem.fromUri(url))
                currentPlayer!!.prepare()
                currentPlayer!!.play()

                playerView.player = currentPlayer
            }
        }

        private fun showEmbed(embedUrl: String) {
            progressBar.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            playerView.visibility = View.GONE
            playIcon.visibility = View.VISIBLE
            errorIcon.visibility = View.GONE
            releasePlayer()

            val gifId = embedUrl.substringAfterLast("/")
            Glide.with(itemView.context)
                .load("https://thumbs.redgifs.com/${gifId}-poster.jpg")
                .centerCrop()
                .into(imageView)
        }

        private fun showImage(url: String) {
            progressBar.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            playerView.visibility = View.GONE
            playIcon.visibility = View.GONE
            errorIcon.visibility = View.GONE
            releasePlayer()

            if (url.startsWith("http")) {
                Glide.with(itemView.context)
                    .load(url)
                    .centerCrop()
                    .into(imageView)
            } else {
                imageView.setImageResource(R.drawable.ic_placeholder)
            }
        }

        private fun releasePlayer() {
            currentPlayer?.let { player ->
                player.pause()
                playerPool.add(player)
                activePlayers.remove(currentPosition)
                currentPlayer = null
            }
            playerView.player = null
        }
    }

    fun releaseAllPlayers() {
        playerPool.forEach { it.release() }
        playerPool.clear()
        activePlayers.values.forEach { it.release() }
        activePlayers.clear()
    }

    class DiffCallback : DiffUtil.ItemCallback<MediaItemState>() {
        override fun areItemsTheSame(oldItem: MediaItemState, newItem: MediaItemState): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: MediaItemState, newItem: MediaItemState): Boolean =
            oldItem == newItem
    }
}