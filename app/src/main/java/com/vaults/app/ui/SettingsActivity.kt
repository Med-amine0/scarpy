package com.vaults.app.ui

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vaults.app.R
import com.vaults.app.VaultsApp
import com.vaults.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { getSharedPreferences("vaults_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar()
        loadSettings()
        setupBackgroundInput()
        setupChangePin()
        setupStorageSection()
    }

    private fun setupToolbar() { binding.btnBack.setOnClickListener { finish() } }

    private fun loadSettings() {
        val bgUrl = prefs.getString("background_url", DEFAULT_BG) ?: DEFAULT_BG
        binding.bgInput.setText(bgUrl)
        val volume = prefs.getInt("default_volume", 5)
        binding.volumeSlider.progress = volume
        binding.volumeLabel.text = "$volume%"
    }

    private fun setupBackgroundInput() {
        binding.btnSaveBg.setOnClickListener {
            val url = binding.bgInput.text?.toString() ?: DEFAULT_BG
            prefs.edit().putString("background_url", url).apply()
            Toast.makeText(this, "Background saved", Toast.LENGTH_SHORT).show()
        }
        binding.btnResetBg.setOnClickListener {
            binding.bgInput.setText(DEFAULT_BG)
            prefs.edit().putString("background_url", DEFAULT_BG).apply()
        }
        binding.volumeSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, u: Boolean) { binding.volumeLabel.text = "$p%" }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                val v = sb?.progress ?: 10
                prefs.edit().putInt("default_volume", v).apply()
                Toast.makeText(this@SettingsActivity, "Volume saved: $v%", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun setupChangePin() { binding.btnChangePin.setOnClickListener { showChangePinDialog() } }

    private fun setupStorageSection() {
        refreshStorageDisplay()
        binding.btnDeleteCache.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Delete cached clips")
                .setMessage("Deletes all downloaded PH clips. Gallery data is kept.")
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { java.io.File(cacheDir, "ph_clips").deleteRecursively() }
                        refreshStorageDisplay()
                        Toast.makeText(this@SettingsActivity, "Cache cleared", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null).show()
        }
        binding.btnDeleteAll.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Delete everything")
                .setMessage("Permanently deletes ALL galleries, items, and cached files. Cannot be undone.")
                .setPositiveButton("Delete all") { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            VaultsApp.instance.db.galleryItemDao().deleteAll()
                            VaultsApp.instance.db.galleryDao().deleteAll()
                            java.io.File(cacheDir, "ph_clips").deleteRecursively()
                        }
                        refreshStorageDisplay()
                        Toast.makeText(this@SettingsActivity, "Everything deleted", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun refreshStorageDisplay() {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                val r = java.io.File(cacheDir, "ph_clips")
                if (r.exists()) r.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else 0L
            }
            binding.storageLabel.text = "Cached clips: ${formatBytes(bytes)}"
        }
    }

    private fun formatBytes(b: Long) = when {
        b >= 1_073_741_824L -> String.format("%.1f GB", b / 1_073_741_824.0)
        b >= 1_048_576L     -> String.format("%.1f MB", b / 1_048_576.0)
        b >= 1024L          -> String.format("%.0f KB", b / 1024.0)
        b > 0L              -> "$b B"
        else                -> "0 MB"
    }

    private fun showChangePinDialog() {
        val input = com.google.android.material.textfield.TextInputEditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        input.hint = "Enter new 4-digit PIN"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.change_pin)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val pin = input.text?.toString() ?: ""
                if (pin.length == 4 && pin.all { it.isDigit() }) {
                    prefs.edit().putString("pin", pin).apply()
                    Toast.makeText(this, "PIN changed", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "PIN must be 4 digits", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    companion object {
        const val DEFAULT_BG = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='40' height='40'%3E%3Cpath fill='%23D92B7A' fill-opacity='0.1' d='M0 0h40v40H0z'/%3E%3Cpath fill='%236B2D8C' fill-opacity='0.1' d='M20 20l20 20-20 20-20-20z'/%3E%3C/svg%3E"
    }
}
