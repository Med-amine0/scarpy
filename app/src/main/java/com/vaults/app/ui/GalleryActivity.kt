package com.vaults.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.vaults.app.R
import com.vaults.app.databinding.ActivityGalleryBinding
import com.vaults.app.db.GalleryType
import com.vaults.app.db.LoadMode
import com.vaults.app.db.ViewMode
import com.vaults.app.vm.GalleryViewModel
import kotlinx.coroutines.launch

class GalleryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGalleryBinding
    private val viewModel: GalleryViewModel by viewModels()
    private lateinit var mediaAdapter: MediaAdapter
    private lateinit var galleryListAdapter: GalleryListAdapter
    private var galleryId: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        galleryId = intent.getLongExtra("gallery_id", 0)
        if (galleryId == 0L) {
            finish()
            return
        }

        lifecycleScope.launch {
            val gallery = viewModel.getGalleryById(galleryId) ?: run {
                finish()
                return@launch
            }
            viewModel.setCurrentGallery(gallery)
            setupUI(gallery)
        }
    }

    private fun setupUI(gallery: com.vaults.app.db.Gallery) {
        binding.title.text = gallery.name
        setupToolbar(gallery.type, gallery.columnCount, gallery.loadMode, gallery.viewMode)

        when {
            gallery.type == GalleryType.FOLDER -> setupFolderView(gallery.id)
            gallery.viewMode == ViewMode.SWIPE -> setupSwipeView(gallery)
            else -> setupMediaView(gallery)
        }
    }

    private fun setupToolbar(type: GalleryType, columns: Int, loadMode: LoadMode, viewMode: ViewMode) {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnSettings.setOnClickListener {
            if (type != GalleryType.FOLDER) {
                showSettingsDialog(columns, loadMode, viewMode)
            }
        }

        binding.fab.setOnClickListener {
            if (type == GalleryType.FOLDER) {
                showCreateSubGalleryDialog()
            } else {
                showAddItemsDialog()
            }
        }
    }

    private fun setupMediaView(gallery: com.vaults.app.db.Gallery) {
        binding.viewPager.visibility = View.GONE
        binding.mediaRecycler.visibility = View.VISIBLE

        mediaAdapter = MediaAdapter(
            galleryType = gallery.type,
            onItemClick = { item ->
                val url = item.resolvedUrl ?: item.thumbnailPath
                openPlayer(url, item.embedUrl != null, gallery.type)
            },
            onItemDelete = { item ->
                lifecycleScope.launch {
                    viewModel.deleteItem(item.id)
                    viewModel.loadGallery(galleryId)
                }
            }
        )

        binding.mediaRecycler.layoutManager = GridLayoutManager(this, gallery.columnCount)
        binding.mediaRecycler.adapter = mediaAdapter
        viewModel.loadGallery(galleryId)

        lifecycleScope.launch {
            viewModel.resolvedItems.collect { items ->
                mediaAdapter.submitList(items.toList())
            }
        }
    }

    private fun setupSwipeView(gallery: com.vaults.app.db.Gallery) {
        binding.mediaRecycler.visibility = View.GONE
        binding.viewPager.visibility = View.VISIBLE

        mediaAdapter = MediaAdapter(
            galleryType = gallery.type,
            onItemClick = { item ->
                val url = item.resolvedUrl ?: item.thumbnailPath
                openPlayer(url, item.embedUrl != null, gallery.type)
            },
            onItemDelete = { item ->
                lifecycleScope.launch {
                    viewModel.deleteItem(item.id)
                    viewModel.loadGallery(galleryId)
                }
            }
        )

        binding.viewPager.adapter = mediaAdapter
        viewModel.loadGallery(galleryId)

        lifecycleScope.launch {
            viewModel.resolvedItems.collect { items ->
                mediaAdapter.submitList(items.toList())
            }
        }
    }

    private fun setupFolderView(parentId: Long) {
        binding.viewPager.visibility = View.GONE
        binding.mediaRecycler.visibility = View.VISIBLE

        galleryListAdapter = GalleryListAdapter(
            onItemClick = { gallery -> openGallery(gallery) },
            onItemRename = { gallery -> showRenameDialog(gallery) },
            onItemDelete = { gallery -> showDeleteDialog(gallery) },
            onItemMove = { from, to ->
                lifecycleScope.launch {
                    viewModel.reorderGalleries(listOf(from, to))
                }
            }
        )

        binding.mediaRecycler.layoutManager = GridLayoutManager(this, 2)
        binding.mediaRecycler.adapter = galleryListAdapter

        viewModel.loadChildGalleries(parentId).observe(this) { galleries ->
            galleryListAdapter.submitList(galleries)
        }

        val touchHelper = ItemTouchHelper(galleryListAdapter.getTouchHelperCallback())
        touchHelper.attachToRecyclerView(binding.mediaRecycler)
    }

    private fun showAddItemsDialog() {
        val input = com.google.android.material.textfield.TextInputEditText(this)
        input.hint = getString(R.string.paste_urls)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_items)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text?.toString() ?: ""
                if (text.isNotBlank()) {
                    viewModel.addItems(galleryId, text)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCreateSubGalleryDialog() {
        val types = arrayOf(
            getString(R.string.type_normal),
            getString(R.string.type_pornhub),
            getString(R.string.type_redgif),
            getString(R.string.type_folder)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_type)
            .setItems(types) { _, which ->
                val type = when (which) {
                    0 -> GalleryType.NORMAL
                    1 -> GalleryType.PORNHUB
                    2 -> GalleryType.REDGIF
                    3 -> GalleryType.FOLDER
                    else -> GalleryType.NORMAL
                }
                showNameDialog(type)
            }
            .show()
    }

    private fun showNameDialog(type: GalleryType) {
        val input = com.google.android.material.textfield.TextInputEditText(this)
        input.hint = getString(R.string.gallery_name)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.create_gallery)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString() ?: "Gallery"
                lifecycleScope.launch {
                    viewModel.createGallery(name, type, galleryId)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRenameDialog(gallery: com.vaults.app.db.Gallery) {
        val input = com.google.android.material.textfield.TextInputEditText(this)
        input.setText(gallery.name)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString() ?: gallery.name
                lifecycleScope.launch {
                    viewModel.updateGallery(gallery.copy(name = name))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteDialog(gallery: com.vaults.app.db.Gallery) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage("Delete ${gallery.name}?")
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    viewModel.deleteGallery(gallery.id)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSettingsDialog(currentColumns: Int, currentLoadMode: LoadMode, currentViewMode: ViewMode) {
        val view = layoutInflater.inflate(R.layout.dialog_gallery_settings, null)
        val slider = view.findViewById<Slider>(R.id.columnSlider)
        val lazySwitch = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchLazy)
        val swipeSwitch = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchSwipe)

        slider.value = currentColumns.toFloat()
        lazySwitch.isChecked = currentLoadMode == LoadMode.ALL
        swipeSwitch.isChecked = currentViewMode == ViewMode.SWIPE

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setView(view)
            .setPositiveButton(R.string.apply) { _, _ ->
                val columns = slider.value.toInt()
                val loadMode = if (lazySwitch.isChecked) LoadMode.ALL else LoadMode.LAZY
                val viewMode = if (swipeSwitch.isChecked) ViewMode.SWIPE else ViewMode.GRID

                lifecycleScope.launch {
                    viewModel.updateGallerySettings(galleryId, columns, loadMode, viewMode)
                    recreate()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openGallery(gallery: com.vaults.app.db.Gallery) {
        val intent = Intent(this, GalleryActivity::class.java).apply {
            putExtra("gallery_id", gallery.id)
        }
        startActivity(intent)
    }

    private fun openPlayer(url: String?, isEmbed: Boolean, type: GalleryType) {
        if (url == null) return

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("url", url)
            putExtra("is_embed", isEmbed)
            putExtra("type", type.name)
        }
        startActivity(intent)
    }
}