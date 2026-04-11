package com.vaults.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.vaults.app.R
import com.vaults.app.VaultsApp
import com.vaults.app.WebViewGalleryActivity
import com.vaults.app.databinding.ActivityMainBinding
import com.vaults.app.db.Gallery
import com.vaults.app.db.GalleryType
import com.vaults.app.vm.GalleryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: GalleryViewModel by viewModels()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupButtons()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.visibility = View.VISIBLE
        binding.backgroundView.visibility = View.GONE

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.webViewClient = object : WebViewClient() {}
        binding.webView.addJavascriptInterface(MainBridge(this), "Android")

        loadFolders()
    }

    private fun setupButtons() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnEdit.setOnClickListener {
            // Toggle edit mode - show delete buttons via JS
            binding.webView.evaluateJavascript("toggleEditMode();", null)
        }

        binding.fab.setOnClickListener {
            showCreateGalleryDialog()
        }
    }

    private fun loadFolders() {
        lifecycleScope.launch {
            val folders = withContext(Dispatchers.IO) {
                VaultsApp.instance.db.galleryDao().getRootGalleriesOnce()
            }

            val foldersJson = JSONArray()
            folders.forEach { folder ->
                val obj = org.json.JSONObject().apply {
                    put("id", folder.id)
                    put("name", folder.name)
                    put("type", folder.type.name)
                    put("columnCount", folder.columnCount)
                }
                foldersJson.put(obj)
            }

            val html = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { 
  background: #121212; 
  min-height: 100vh;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: #1e1e1e;
  position: sticky;
  top: 0;
  z-index: 100;
}
.toolbar h1 {
  color: #ff69b4;
  font-size: 20px;
  font-weight: bold;
}
.toolbar-btns button {
  background: transparent;
  border: none;
  color: #fff;
  padding: 8px;
  cursor: pointer;
}
.folder-grid { 
  display: grid; 
  grid-template-columns: repeat(2, 1fr); 
  gap: 12px; 
  padding: 12px; 
}
.folder { 
  aspect-ratio: 1; 
  background: #2a2a2a; 
  border-radius: 12px; 
  display: flex; 
  flex-direction: column;
  align-items: center; 
  justify-content: center;
  cursor: pointer;
  transition: transform 0.2s;
}
.folder:active { transform: scale(0.95); }
.folder-icon { font-size: 32px; margin-bottom: 8px; }
.folder-name { 
  color: #ff69b4; 
  font-size: 14px; 
  text-align: center;
  padding: 0 8px;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.folder-type {
  color: #888;
  font-size: 10px;
  margin-top: 4px;
}
.delete-btn {
  display: none;
  position: absolute;
  top: -8px;
  right: -8px;
  width: 24px;
  height: 24px;
  background: #f44336;
  border-radius: 12px;
  border: none;
  color: #fff;
  font-size: 14px;
  cursor: pointer;
}
.edit-mode .folder { position: relative; }
.edit-mode .delete-btn { display: block; }
</style>
</head>
<body>
<div class="toolbar">
  <h1>Vaults</h1>
  <div class="toolbar-btns">
    <button onclick="Android.showAddDialog()">+</button>
  </div>
</div>
<div class="folder-grid" id="folders"></div>
<script>
var folders = $foldersJson;
var editMode = false;

function renderFolders() {
  var grid = document.getElementById('folders');
  grid.innerHTML = '';
  folders.forEach(function(folder) {
    var div = document.createElement('div');
    div.className = 'folder';
    div.onclick = function() { Android.openGallery(folder.id); };
    
    var icon = folder.type === 'FOLDER' ? '📁' : 
               folder.type === 'PORNHUB' ? '🔞' : 
               folder.type === 'REDGIF' ? '👹' : '🖼️';
    
    div.innerHTML = '<div class="folder-icon">' + icon + '</div>' +
                    '<div class="folder-name">' + folder.name + '</div>' +
                    '<div class="folder-type">' + folder.type + '</div>' +
                    '<button class="delete-btn" onclick="event.stopPropagation(); deleteFolder(' + folder.id + ')">×</button>';
    grid.appendChild(div);
  });
}

function toggleEditMode() {
  editMode = !editMode;
  document.body.classList.toggle('edit-mode', editMode);
}

function deleteFolder(id) {
  if (confirm('Delete this gallery?')) {
    Android.deleteGallery(id);
  }
}

renderFolders();
</script>
</body>
</html>
            """.trimIndent()

            binding.webView.loadDataWithBaseURL("https://vaults.app/", html, "text/html", "UTF-8", null)
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
        val input = TextInputEditText(this)
        input.hint = getString(R.string.gallery_name)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.create_gallery)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString() ?: "Gallery"
                lifecycleScope.launch {
                    viewModel.createGallery(name, type)
                    loadFolders()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    inner class MainBridge(private val context: Context) {
        @JavascriptInterface
        fun openGallery(galleryId: Long) {
            val intent = Intent(context, WebViewGalleryActivity::class.java).apply {
                putExtra("gallery_id", galleryId)
            }
            startActivity(intent)
        }

        @JavascriptInterface
        fun showAddDialog() {
            runOnUiThread { showCreateGalleryDialog() }
        }

        @JavascriptInterface
        fun deleteGallery(galleryId: Long) {
            lifecycleScope.launch {
                viewModel.deleteGallery(galleryId)
                loadFolders()
            }
        }
    }
}