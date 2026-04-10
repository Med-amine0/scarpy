package com.vaults.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vaults.app.R
import com.vaults.app.db.Gallery
import com.vaults.app.db.GalleryType
import androidx.recyclerview.widget.ItemTouchHelper

class GalleryAdapter(
    private val onItemClick: (Gallery) -> Unit,
    private val onItemRename: (Gallery) -> Unit,
    private val onItemDelete: (Gallery) -> Unit,
    private val onItemMove: (Gallery, Gallery) -> Unit
) : ListAdapter<Gallery, GalleryAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.galleryName)
        private val typeLabel: TextView = itemView.findViewById(R.id.typeLabel)
        private val btnRename: ImageButton = itemView.findViewById(R.id.btnRename)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

        fun bind(gallery: Gallery) {
            nameText.text = gallery.name
            typeLabel.text = when (gallery.type) {
                GalleryType.NORMAL -> "\uD83D\uDDBC️ Normal"
                GalleryType.PORNHUB -> "\uD83D\uDE33 PornHub"
                GalleryType.REDGIF -> "\uD83D\uDE08 RedGif"
                GalleryType.FOLDER -> "\uD83D\uDCC1 Folder"
            }

            itemView.setOnClickListener { onItemClick(gallery) }

            btnRename.setOnClickListener { onItemRename(gallery) }
            btnDelete.setOnClickListener { onItemDelete(gallery) }
        }
    }

    fun getTouchHelperCallback(): ItemTouchHelper.SimpleCallback {
        return object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = getItem(viewHolder.adapterPosition)
                val to = getItem(target.adapterPosition)
                onItemMove(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean = true
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Gallery>() {
        override fun areItemsTheSame(oldItem: Gallery, newItem: Gallery): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Gallery, newItem: Gallery): Boolean =
            oldItem == newItem
    }
}