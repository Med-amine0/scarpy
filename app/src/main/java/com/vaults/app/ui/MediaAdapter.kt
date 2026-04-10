package com.vaults.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import com.vaults.app.vm.GalleryViewModel
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class MediaAdapter(
    private val galleryType: GalleryType,
    private val onItemClick: (GalleryViewModel.ResolvedItem) -> Unit,
    private val onItemDelete: (GalleryViewModel.ResolvedItem) -> Unit
) : ListAdapter<GalleryViewModel.ResolvedItem, MediaAdapter.ViewHolder>(DiffCallback()) {

    private val players = ConcurrentHashMap<Long, ExoPlayer>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.releasePlayer()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container: FrameLayout = itemView.findViewById(R.id.mediaContainer)
        private val imageView: ImageView = itemView.findViewById(R.id.thumbnailView)
        private val playerView: androidx.media3.ui.PlayerView = itemView.findViewById(R.id.playerView)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        private val playIcon: ImageView = itemView.findViewById(R.id.playIcon)
        private val errorIcon: ImageView = itemView.findViewById(R.id.errorIcon)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

        private var currentItem: GalleryViewModel.ResolvedItem? = null
        private var currentPlayer: ExoPlayer? = null

        fun bind(item: GalleryViewModel.ResolvedItem) {
            currentItem = item

            when {
                item.isLoading -> {
                    showLoading()
                }
                item.error != null -> {
                    showError()
                }
                else -> {
                    showThumbnail(item)
                }
            }

            btnDelete.visibility = View.GONE

            itemView.setOnClickListener {
                onItemClick(item)
            }

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

        private fun showThumbnail(item: GalleryViewModel.ResolvedItem) {
            val hasLocalThumb = item.thumbnailPath != null && File(item.thumbnailPath).exists()
            val shouldAutoplay = galleryType == GalleryType.PORNHUB
            val hasUrl = item.resolvedUrl != null && (galleryType == GalleryType.PORNHUB || galleryType == GalleryType.NORMAL)

            if (hasLocalThumb && shouldAutoplay && hasUrl) {
                showVideoThumb(item)
            } else if (hasLocalThumb) {
                showImageThumb(item.thumbnailPath!!)
            } else if (galleryType == GalleryType.REDGIF && item.embedUrl != null) {
                showRedGifEmbed(item.embedUrl)
            } else {
                showPlaceholder()
            }
        }

        private fun showImageThumb(path: String) {
            progressBar.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            playerView.visibility = View.GONE
            playIcon.visibility = View.GONE
            errorIcon.visibility = View.GONE

            Glide.with(itemView.context)
                .load(File(path))
                .centerCrop()
                .into(imageView)
        }

        private fun showVideoThumb(item: GalleryViewModel.ResolvedItem) {
            progressBar.visibility = View.GONE
            imageView.visibility = View.GONE
            playerView.visibility = View.VISIBLE
            playIcon.visibility = View.GONE
            errorIcon.visibility = View.GONE

            releasePlayer()

            val player = ExoPlayer.Builder(itemView.context).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                playWhenReady = true
            }

            item.resolvedUrl?.let { url ->
                val mediaItem = MediaItem.fromUri(url)
                player.setMediaItem(mediaItem)
                player.prepare()
            }

            playerView.player = player
            currentPlayer = player
            players[item.id] = player
        }

        private fun showRedGifEmbed(embedUrl: String) {
            progressBar.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            playerView.visibility = View.GONE
            playIcon.visibility = View.VISIBLE
            errorIcon.visibility = View.GONE

            Glide.with(itemView.context)
                .load("https://redgifs.com/ifr/${embedUrl.substringAfterLast("/")}")
                .centerCrop()
                .into(imageView)
        }

        private fun showPlaceholder() {
            progressBar.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            playerView.visibility = View.GONE
            playIcon.visibility = View.VISIBLE
            errorIcon.visibility = View.GONE

            imageView.setImageResource(R.drawable.ic_placeholder)
        }

        fun releasePlayer() {
            currentPlayer?.release()
            currentItem?.let { players.remove(it.id) }
            currentPlayer = null
        }
    }

    fun releaseAllPlayers() {
        players.values.forEach { it.release() }
        players.clear()
    }

    class DiffCallback : DiffUtil.ItemCallback<GalleryViewModel.ResolvedItem>() {
        override fun areItemsTheSame(
            oldItem: GalleryViewModel.ResolvedItem,
            newItem: GalleryViewModel.ResolvedItem
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: GalleryViewModel.ResolvedItem,
            newItem: GalleryViewModel.ResolvedItem
        ): Boolean = oldItem == newItem
    }
}