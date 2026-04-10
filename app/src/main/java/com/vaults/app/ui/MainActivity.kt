package com.vaults.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vaults.app.R
import com.vaults.app.databinding.ActivityMainBinding
import com.vaults.app.db.Gallery
import com.vaults.app.db.GalleryType
import com.vaults.app.vm.GalleryViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: GalleryViewModel by viewModels()
    private lateinit var adapter: GalleryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecycler()
        setupFab()
    }

    private fun setupToolbar() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnEdit.setOnClickListener {
            viewModel.toggleEditMode()
        }
    }

    private fun setupRecycler() {
        adapter = GalleryAdapter(
            onItemClick = { gallery -> openGallery(gallery) },
            onItemRename = { gallery -> showRenameDialog(gallery) },
            onItemDelete = { gallery -> showDeleteDialog(gallery) },
            onItemMove = { from, to -> 
                lifecycleScope.launch { 
                    viewModel.reorderGalleries(listOf(from, to)) 
                } 
            }
        )

        binding.recycler.layoutManager = GridLayoutManager(this, 2)
        binding.recycler.adapter = adapter

        viewModel.rootGalleries.observe(this) { galleries ->
            adapter.submitList(galleries)
        }

        val touchHelper = ItemTouchHelper(adapter.getTouchHelperCallback())
        touchHelper.attachToRecyclerView(binding.recycler)
    }

    private fun setupFab() {
        binding.fab.setOnClickListener {
            showCreateGalleryDialog()
        }
    }

    private fun showCreateGalleryDialog() {
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
                    viewModel.createGallery(name, type)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openGallery(gallery: Gallery) {
        val intent = Intent(this, GalleryActivity::class.java).apply {
            putExtra("gallery_id", gallery.id)
        }
        startActivity(intent)
    }

    private fun showRenameDialog(gallery: Gallery) {
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

    private fun showDeleteDialog(gallery: Gallery) {
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
}