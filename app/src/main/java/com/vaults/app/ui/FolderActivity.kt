package com.vaults.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.vaults.app.R
import com.vaults.app.VaultsApp
import com.vaults.app.WebViewGalleryActivity
import com.vaults.app.databinding.ActivityWebviewGalleryBinding
import com.vaults.app.db.Gallery
import com.vaults.app.db.GalleryType
import com.vaults.app.vm.GalleryViewModel
import androidx.activity.viewModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class FolderActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWebviewGalleryBinding
    private val viewModel: GalleryViewModel by viewModels()
    private var folderId: Long = 0
    private var folderName: String = "Folder"

    companion object {
        const val EXTRA_FOLDER_ID = "folder_id"
        const val EXTRA_FOLDER_NAME = "folder_name"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        binding = ActivityWebviewGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        folderId = intent.getLongExtra(EXTRA_FOLDER_ID, 0)
        folderName = intent.getStringExtra(EXTRA_FOLDER_NAME) ?: "Folder"

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowContentAccess = true
            allowFileAccess = true
        }
        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.webViewClient = WebViewClient()
        binding.webView.addJavascriptInterface(FolderBridge(this), "Android")

        loadGalleries()
    }

    override fun onResume() {
        super.onResume()
        loadGalleries()
    }

    private fun loadGalleries() {
        lifecycleScope.launch {
            val galleries = withContext(Dispatchers.IO) {
                VaultsApp.instance.db.galleryDao().getChildGalleriesOnce(folderId)
            }

            val galJson = JSONArray()
            galleries.forEach { g ->
                val count = withContext(Dispatchers.IO) {
                    if (g.type == com.vaults.app.db.GalleryType.FOLDER)
                        VaultsApp.instance.db.galleryDao().countChildGalleries(g.id)
                    else
                        VaultsApp.instance.db.galleryItemDao().countItems(g.id)
                }
                galJson.put(JSONObject().apply {
                    put("id", g.id)
                    put("name", g.name)
                    put("type", g.type.name)
                    put("count", count)
                })
            }

            val escapedName = folderName.replace("'", "\\'")
            val html = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
* { margin:0; padding:0; box-sizing:border-box; }
body { background:#121212; min-height:100vh; }
.toolbar {
  display:flex; align-items:center;
  padding:12px 16px;
  padding-top: calc(12px + env(safe-area-inset-top));
  background:#1e1e1e;
  position:sticky; top:0; z-index:100; gap:12px;
}
.back-btn { background:transparent; border:none; color:#ff69b4; font-size:24px; cursor:pointer; }
.toolbar h1 { color:#ff69b4; font-size:18px; font-weight:bold; flex:1; }
.toolbar-btn { background:transparent; border:none; color:#fff; font-size:20px; cursor:pointer; }
.grid { display:grid; grid-template-columns:repeat(2,1fr); gap:12px; padding:12px; }
.card {
  background:#2a2a2a; border-radius:12px;
  display:flex; flex-direction:column; align-items:center; justify-content:center;
  aspect-ratio:1; cursor:pointer; position:relative;
  transition:transform 0.15s;
}
.card:active { transform:scale(0.95); }
.card-icon { font-size:32px; margin-bottom:8px; }
.card-name { color:#ff69b4; font-size:14px; text-align:center; padding:0 8px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; max-width:100%; }
.card-type { color:#888; font-size:10px; margin-top:4px; }
.card-count { color:#aaa; font-size:11px; margin-top:3px; }
.del-btn {
  display:none; position:absolute; top:-8px; right:-8px;
  width:26px; height:26px; background:#f44336; border-radius:13px;
  border:none; color:#fff; font-size:15px; cursor:pointer;
}
.edit-mode .del-btn { display:block; }
.add-fab {
  position:fixed; bottom:24px; right:24px;
  width:56px; height:56px; background:#ff69b4; border-radius:28px;
  border:none; color:#fff; font-size:28px; cursor:pointer;
  box-shadow:0 4px 12px rgba(0,0,0,0.3); z-index:100;
}
.empty { grid-column:1/-1; text-align:center; padding:48px; color:#555; font-size:15px; }
</style>
</head>
<body>
<div class="toolbar">
  <button class="back-btn" onclick="Android.goBack()">←</button>
  <h1>$escapedName</h1>
  <button class="toolbar-btn" onclick="toggleEdit()">✏️</button>
</div>
<div class="grid" id="grid"></div>
<button class="add-fab" onclick="Android.showAddDialog()">+</button>
<script>
var galleries = $galJson;
var editMode = false;

function icon(type) {
  if (type === 'CLIPS') return '🎬';
  if (type === 'REDGIF') return '🔥';
  return '🖼️';
}

function render() {
  var grid = document.getElementById('grid');
  grid.innerHTML = '';
  if (galleries.length === 0) {
    grid.innerHTML = '<div class="empty">No galleries yet.<br>Tap + to add one.</div>';
    return;
  }
  galleries.forEach(function(g) {
    var card = document.createElement('div');
    card.className = 'card';
    card.innerHTML =
      '<div class="card-icon">' + icon(g.type) + '</div>' +
      '<div class="card-name">' + g.name + '</div>' +
      '<div class="card-type">' + g.type + '</div>' +
      '<div class="card-count">' + g.count + '</div>' +
      '<button class="del-btn" onclick="event.stopPropagation();deleteGallery(' + g.id + ')">×</button>';
    card.onclick = function() { Android.openGallery(g.id); };

    // Long-press to rename
    var pressTimer = null;
    card.addEventListener('touchstart', function(e) {
      pressTimer = setTimeout(function() { Android.renameGallery(g.id, g.name); }, 600);
    });
    card.addEventListener('touchend', function() { clearTimeout(pressTimer); });
    card.addEventListener('touchmove', function() { clearTimeout(pressTimer); });

    grid.appendChild(card);
  });
}

function toggleEdit() {
  editMode = !editMode;
  document.body.classList.toggle('edit-mode', editMode);
}

function deleteGallery(id) {
  if (confirm('Delete this gallery?')) Android.deleteGallery(id);
}

render();
</script>
</body>
</html>
            """.trimIndent()

            binding.webView.loadDataWithBaseURL("https://app.vaults.local", html, "text/html", "UTF-8", null)
        }
    }

    private fun showAddDialog() {
        val types = arrayOf("Normal (Images/Video)", "Clips", "RedGif")
        MaterialAlertDialogBuilder(this)
            .setTitle("Gallery type")
            .setItems(types) { _, which ->
                val type = when (which) {
                    0 -> GalleryType.NORMAL
                    1 -> GalleryType.CLIPS
                    else -> GalleryType.REDGIF
                }
                showNameDialog(type)
            }.show()
    }

    private fun showNameDialog(type: GalleryType) {
        val input = TextInputEditText(this)
        input.hint = "Gallery name"
        MaterialAlertDialogBuilder(this)
            .setTitle("New gallery")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text?.toString()?.takeIf { it.isNotBlank() } ?: "Gallery"
                lifecycleScope.launch {
                    viewModel.createGallery(name, type, parentId = folderId)
                    loadGalleries()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class FolderBridge(private val ctx: Context) {
        @JavascriptInterface
        fun goBack() { finish() }

        @JavascriptInterface
        fun showAddDialog() { runOnUiThread { this@FolderActivity.showAddDialog() } }

        @JavascriptInterface
        fun openGallery(galleryId: Long) {
            startActivity(Intent(ctx, WebViewGalleryActivity::class.java).apply {
                putExtra("gallery_id", galleryId)
            })
        }

        @JavascriptInterface
        fun deleteGallery(galleryId: Long) {
            lifecycleScope.launch {
                viewModel.deleteGallery(galleryId)
                loadGalleries()
            }
        }

        @JavascriptInterface
        fun renameGallery(galleryId: Long, currentName: String) {
            runOnUiThread {
                val input = TextInputEditText(ctx)
                input.setText(currentName)
                input.hint = "Gallery name"
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("Rename")
                    .setView(input)
                    .setPositiveButton("Save") { _, _ ->
                        val newName = input.text?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return@setPositiveButton
                        lifecycleScope.launch {
                            val gallery = withContext(Dispatchers.IO) {
                                VaultsApp.instance.db.galleryDao().getGalleryById(galleryId)
                            }
                            if (gallery != null) {
                                withContext(Dispatchers.IO) {
                                    VaultsApp.instance.db.galleryDao().update(gallery.copy(name = newName))
                                }
                                loadGalleries()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
}
